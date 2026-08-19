// Verifies a Swift-generated lean_poa proof with the chain's own verifier —
// the same bb v5.0.0 build earth-1 runs. This is the real end of the Phase 1
// loop: proving on Apple silicon is only useful if this accepts the result.
package main

import (
	"fmt"
	"os"
	"strings"

	"github.com/burnt-labs/barretenberg-go/barretenberg"
)

func read(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "read %s: %v\n", path, err)
		os.Exit(2)
	}
	return strings.TrimSpace(string(b))
}

func main() {
	dir := os.Args[1]

	// The Phase 1 gate writes `swift_*`; `progate --witness` writes
	// `passport_*` for a proof built from a passport rather than from the
	// checked-in witness. Same verifier either way.
	prefix := "swift"
	if len(os.Args) > 2 {
		prefix = os.Args[2]
	}

	vkHex := read(dir + "/" + prefix + "_vk.hex")
	proofHex := read(dir + "/" + prefix + "_proof_body.hex")
	signals := strings.Split(read(dir+"/"+prefix+"_public_signals.txt"), "\n")

	fmt.Printf("vk        %d hex chars\n", len(vkHex))
	fmt.Printf("proof     %d hex chars\n", len(proofHex))
	fmt.Printf("signals   %v\n", signals)

	verifier, err := barretenberg.NewVerifierFromHex(vkHex)
	if err != nil {
		fmt.Fprintf(os.Stderr, "parse vk: %v\n", err)
		os.Exit(1)
	}
	defer verifier.Close()

	proof, err := barretenberg.ParseProofHex(proofHex)
	if err != nil {
		fmt.Fprintf(os.Stderr, "parse proof: %v\n", err)
		os.Exit(1)
	}

	ok, err := verifier.Verify(proof, signals)
	if err != nil {
		fmt.Fprintf(os.Stderr, "verify: %v\n", err)
		os.Exit(1)
	}
	if !ok {
		fmt.Println("\nREJECTED — the chain verifier does not accept this proof")
		os.Exit(1)
	}
	fmt.Println("\nACCEPTED — the chain verifier accepts the Swift-generated proof")
}
