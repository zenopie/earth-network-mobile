// certcheck prints what the chain makes of a certificate, so the Swift
// certificate parser can be compared against it rather than against itself.
//
// The value that matters is CanonicalBytes: the bytes the register circuits
// hash into the DSC commitment. The circuit returns that commitment as a public
// output and the chain recomputes it from the certificate in MsgRegister, so a
// disagreement here is a registration that fails on chain with nothing in the
// app to explain it.
//
//	cd tools/certcheck && go run . > vectors.json
package main

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/earth-network/earth/x/pki/certs"
)

// The chain's own test corpus. Real certificates, which matters: crypto/x509
// cannot even encode Brainpool, so a generated fixture could not cover the
// curves most European DSCs actually use.
var sources = []string{
	"../../../earth-network-chain/x/pki/certs/testdata",
	"../../../earth-network-chain/x/pki/keeper/testdata",
}

type vector struct {
	File           string `json:"file"`
	KeyType        string `json:"key_type"`
	Curve          string `json:"curve,omitempty"`
	CanonicalHex   string `json:"canonical_hex"`
	CanonicalBytes int    `json:"canonical_len"`
	ModulusBits    int    `json:"modulus_bits,omitempty"`
}

func main() {
	// `certcheck <witness.json> <public_signals.txt>` closes the last gap in
	// the passport pipeline: the circuit returns a DSC commitment as its third
	// public signal, and the chain recomputes one from the certificate in
	// MsgRegister. If the Swift canonical encoding were wrong the two would
	// differ, and a registration would fail on chain with nothing in the app
	// to explain it.
	if len(os.Args) > 2 {
		os.Exit(checkCommitment(os.Args[1], os.Args[2]))
	}

	var out []vector

	for _, dir := range sources {
		entries, err := os.ReadDir(dir)
		if err != nil {
			fmt.Fprintf(os.Stderr, "skipping %s: %v\n", dir, err)
			continue
		}
		for _, entry := range entries {
			if filepath.Ext(entry.Name()) != ".der" {
				continue
			}
			path := filepath.Join(dir, entry.Name())
			der, err := os.ReadFile(path)
			if err != nil {
				fmt.Fprintf(os.Stderr, "%s: %v\n", path, err)
				continue
			}
			cert, err := certs.ParseCert(der)
			if err != nil {
				fmt.Fprintf(os.Stderr, "%s: parse: %v\n", entry.Name(), err)
				continue
			}
			v := vector{
				File:           entry.Name(),
				CanonicalHex:   hex.EncodeToString(cert.PublicKey.CanonicalBytes()),
				CanonicalBytes: len(cert.PublicKey.CanonicalBytes()),
			}
			if cert.PublicKey.IsRSA {
				v.KeyType = "RSA"
				v.ModulusBits = cert.PublicKey.RSAModulus.BitLen()
			} else {
				v.KeyType = "ECDSA"
				v.Curve = cert.PublicKey.Curve.Name
			}
			out = append(out, v)
		}
	}

	sort.Slice(out, func(i, j int) bool { return out[i].File < out[j].File })
	encoded, err := json.MarshalIndent(out, "", "  ")
	if err != nil {
		panic(err)
	}
	fmt.Println(string(encoded))
}


// checkCommitment reads the SOD the Swift side built, parses its Document
// Signer with the chain's own code, and compares the resulting commitment with
// the one the circuit returned.
func checkCommitment(witnessPath, signalsPath string) int {
	raw, err := os.ReadFile(witnessPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read %s: %v\n", witnessPath, err)
		return 2
	}
	var fixture struct {
		SODHex string `json:"sod_hex"`
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		fmt.Fprintf(os.Stderr, "parse %s: %v\n", witnessPath, err)
		return 2
	}
	sod, err := hex.DecodeString(fixture.SODHex)
	if err != nil {
		fmt.Fprintf(os.Stderr, "sod_hex: %v\n", err)
		return 2
	}

	// The Document Signer certificate, found the way the chain finds it.
	der, err := dscFromSOD(sod)
	if err != nil {
		fmt.Fprintf(os.Stderr, "extract DSC: %v\n", err)
		return 2
	}
	cert, err := certs.ParseCert(der)
	if err != nil {
		fmt.Fprintf(os.Stderr, "parse DSC: %v\n", err)
		return 2
	}

	canonical := cert.PublicKey.CanonicalBytes()
	commitment := certs.DscCommitment(canonical)
	got := commitment.String()

	signalsRaw, err := os.ReadFile(signalsPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read %s: %v\n", signalsPath, err)
		return 2
	}
	signals := strings.Split(strings.TrimSpace(string(signalsRaw)), "\n")
	if len(signals) < 3 {
		fmt.Fprintf(os.Stderr, "expected 3 public signals, got %d\n", len(signals))
		return 2
	}
	want := strings.TrimSpace(signals[2])

	fmt.Printf("canonical key   %d bytes  %s\n", len(canonical), hex.EncodeToString(canonical))
	fmt.Printf("chain           %s\n", got)
	fmt.Printf("circuit signal  %s\n", want)
	if got != want {
		fmt.Println("\nMISMATCH — the chain would reject this registration")
		return 1
	}
	fmt.Println("\nMATCH — the chain recomputes the commitment the circuit returned")
	return 0
}

// dscFromSOD walks EF.SOD far enough to hand back the signer's certificate.
// Deliberately not shared with the Swift implementation: the point is that two
// independent walks of the same bytes reach the same certificate.
func dscFromSOD(sod []byte) ([]byte, error) {
	read := func(b []byte) (tag byte, content, rest []byte, err error) {
		if len(b) < 2 {
			return 0, nil, nil, fmt.Errorf("truncated")
		}
		tag = b[0]
		i := 1
		n := int(b[i])
		i++
		if n&0x80 != 0 {
			count := n & 0x7f
			if count == 0 || count > 4 || len(b) < i+count {
				return 0, nil, nil, fmt.Errorf("bad length")
			}
			n = 0
			for j := 0; j < count; j++ {
				n = n<<8 | int(b[i+j])
			}
			i += count
		}
		if len(b) < i+n {
			return 0, nil, nil, fmt.Errorf("truncated value")
		}
		return tag, b[i : i+n], b[i+n:], nil
	}

	// [APPLICATION 23] ContentInfo, if present.
	if len(sod) > 0 && sod[0] == 0x77 {
		_, content, _, err := read(sod)
		if err != nil {
			return nil, err
		}
		sod = content
	}
	_, contentInfo, _, err := read(sod) // SEQUENCE
	if err != nil {
		return nil, err
	}
	_, _, afterOID, err := read(contentInfo) // contentType OID
	if err != nil {
		return nil, err
	}
	_, explicit, _, err := read(afterOID) // [0] EXPLICIT
	if err != nil {
		return nil, err
	}
	_, signedData, _, err := read(explicit) // SEQUENCE SignedData
	if err != nil {
		return nil, err
	}

	rest := signedData
	for len(rest) > 0 {
		tag, content, next, err := read(rest)
		if err != nil {
			return nil, err
		}
		if tag == 0xa0 { // [0] IMPLICIT certificates
			_, _, _, err := read(content)
			if err != nil {
				return nil, err
			}
			// The first certificate, with its tag and length intact.
			total := len(content) - len(mustRest(content, read))
			return content[:total], nil
		}
		rest = next
	}
	return nil, fmt.Errorf("no certificates in SignedData")
}

func mustRest(b []byte, read func([]byte) (byte, []byte, []byte, error)) []byte {
	_, _, rest, err := read(b)
	if err != nil {
		return nil
	}
	return rest
}
