// txcheck verifies that a transaction built by the Swift wallet is the
// transaction the chain thinks it is.
//
// EarthCore encodes protobuf by hand — see ios/EarthCore/Sources/EarthCore/
// Cosmos/Protobuf.swift for why — so nothing in Swift can show the bytes are
// right. This decodes them with the cosmos-sdk's own types, rebuilds the
// SignDoc the way the chain's ante handler does, and verifies the secp256k1
// signature against the public key the transaction carries. That is the same
// arrangement as tools/chainverify: the Swift side asserts what it can, and
// the chain's own code decides.
//
//	cd ios/EarthCore && swift run corecheck     # writes .artifacts/tx.json
//	cd tools/txcheck && go run . ../../ios/EarthCore/.artifacts
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"github.com/cosmos/cosmos-sdk/crypto/keys/secp256k1"
	"github.com/cosmos/cosmos-sdk/types/bech32"
	txtypes "github.com/cosmos/cosmos-sdk/types/tx"
	signingtypes "github.com/cosmos/cosmos-sdk/types/tx/signing"
	banktypes "github.com/cosmos/cosmos-sdk/x/bank/types"
	govv1 "github.com/cosmos/cosmos-sdk/x/gov/types/v1"
)

type fixture struct {
	Mnemonic      string `json:"mnemonic"`
	Address       string `json:"address"`
	PubKeyHex     string `json:"pubkey_hex"`
	ChainID       string `json:"chain_id"`
	AccountNumber uint64 `json:"account_number"`
	Sequence      uint64 `json:"sequence"`
	GasLimit      uint64 `json:"gas_limit"`
	FeeUerth      string `json:"fee_uerth"`
	Memo          string `json:"memo"`
	Recipient     string `json:"recipient"`
	SendAmount    string `json:"send_amount"`
	TxBytesB64    string `json:"tx_bytes_base64"`
	SignDocB64    string `json:"sign_doc_base64"`
	SignatureHex  string `json:"signature_hex"`
	VoteProposal  uint64 `json:"vote_proposal_id"`
}

var failures int

func check(label string, ok bool, detail ...any) {
	if ok {
		fmt.Printf("  ok    %s\n", label)
		return
	}
	failures++
	fmt.Printf("  FAIL  %s\n", label)
	for _, d := range detail {
		fmt.Printf("        %v\n", d)
	}
}

func equal[T comparable](label string, actual, expected T) {
	check(label, actual == expected,
		fmt.Sprintf("expected %v", expected), fmt.Sprintf("actual   %v", actual))
}

func main() {
	dir := "../../ios/EarthCore/.artifacts"
	if len(os.Args) > 1 {
		dir = os.Args[1]
	}
	raw, err := os.ReadFile(filepath.Join(dir, "tx.json"))
	if err != nil {
		fmt.Fprintf(os.Stderr, "no fixture: %v\nRun `cd ios/EarthCore && swift run corecheck` first.\n", err)
		os.Exit(2)
	}
	var f fixture
	if err := json.Unmarshal(raw, &f); err != nil {
		panic(err)
	}

	txBytes, err := base64.StdEncoding.DecodeString(f.TxBytesB64)
	if err != nil {
		panic(err)
	}

	// Unmarshalled through the generated types directly rather than through a
	// ProtoCodec. A codec would also try to resolve every Any against an
	// interface registry, and the earth messages are not in this module's
	// graph — their bytes are checked field by field further down instead.
	fmt.Println("\ndecoding")

	var txRaw txtypes.TxRaw
	check("TxRaw unmarshals", txRaw.Unmarshal(txBytes) == nil)
	equal("carries one signature", len(txRaw.Signatures), 1)

	var body txtypes.TxBody
	check("TxBody unmarshals", body.Unmarshal(txRaw.BodyBytes) == nil)
	equal("two messages", len(body.Messages), 2)
	equal("memo", body.Memo, f.Memo)
	equal("no timeout height", body.TimeoutHeight, uint64(0))

	var authInfo txtypes.AuthInfo
	check("AuthInfo unmarshals", authInfo.Unmarshal(txRaw.AuthInfoBytes) == nil)
	equal("one signer", len(authInfo.SignerInfos), 1)

	fmt.Println("\nauth info")

	signer := authInfo.SignerInfos[0]
	equal("sequence", signer.Sequence, f.Sequence)
	equal("SIGN_MODE_DIRECT",
		signer.ModeInfo.GetSingle().Mode, signingtypes.SignMode_SIGN_MODE_DIRECT)
	equal("pubkey type url", signer.PublicKey.TypeUrl, "/cosmos.crypto.secp256k1.PubKey")

	var pubKey secp256k1.PubKey
	check("pubkey unmarshals", pubKey.Unmarshal(signer.PublicKey.Value) == nil)
	equal("pubkey bytes", hex.EncodeToString(pubKey.Key), f.PubKeyHex)

	addr, err := bech32.ConvertAndEncode("earth", pubKey.Address().Bytes())
	check("address derives from the pubkey in the tx", err == nil && addr == f.Address,
		fmt.Sprintf("expected %s", f.Address), fmt.Sprintf("actual   %s", addr))

	equal("gas limit", authInfo.Fee.GasLimit, f.GasLimit)
	equal("one fee coin", len(authInfo.Fee.Amount), 1)
	if len(authInfo.Fee.Amount) == 1 {
		equal("fee denom", authInfo.Fee.Amount[0].Denom, "uerth")
		equal("fee amount", authInfo.Fee.Amount[0].Amount.String(), f.FeeUerth)
	}

	fmt.Println("\nsignature")

	// Rebuilt here rather than taken from the fixture: this is the document the
	// chain re-derives, and it has to match byte for byte or the signature is
	// over something else.
	signDoc := txtypes.SignDoc{
		BodyBytes:     txRaw.BodyBytes,
		AuthInfoBytes: txRaw.AuthInfoBytes,
		ChainId:       f.ChainID,
		AccountNumber: f.AccountNumber,
	}
	signBytes, err := signDoc.Marshal()
	if err != nil {
		panic(err)
	}
	swiftSignDoc, _ := base64.StdEncoding.DecodeString(f.SignDocB64)
	check("SignDoc re-encodes to the same bytes Swift signed",
		bytes.Equal(signBytes, swiftSignDoc),
		"go:    "+hex.EncodeToString(signBytes),
		"swift: "+hex.EncodeToString(swiftSignDoc))

	check("signature verifies under the tx's own pubkey",
		pubKey.VerifySignature(signBytes, txRaw.Signatures[0]))
	equal("signature is the one Swift reported",
		hex.EncodeToString(txRaw.Signatures[0]), f.SignatureHex)

	// A signature that still verifies after the body changes would mean the
	// SignDoc is not committing to it.
	tampered := append([]byte{}, signBytes...)
	tampered[len(tampered)-1] ^= 0x01
	check("a tampered SignDoc fails", !pubKey.VerifySignature(tampered, txRaw.Signatures[0]))

	fmt.Println("\nmessages")

	equal("first message", body.Messages[0].TypeUrl, "/cosmos.bank.v1beta1.MsgSend")
	var send banktypes.MsgSend
	if send.Unmarshal(body.Messages[0].Value) == nil {
		equal("MsgSend from", send.FromAddress, f.Address)
		equal("MsgSend to", send.ToAddress, f.Recipient)
		equal("MsgSend coins", len(send.Amount), 1)
		if len(send.Amount) == 1 {
			equal("MsgSend denom", send.Amount[0].Denom, "uerth")
			equal("MsgSend amount", send.Amount[0].Amount.String(), f.SendAmount)
			// A round trip through the SDK's own marshaller: if the Swift
			// encoding differed in field order or in emitting a default, this
			// would come back different.
			reencoded, _ := send.Marshal()
			check("MsgSend re-encodes identically",
				bytes.Equal(reencoded, body.Messages[0].Value))
		}
	} else {
		check("MsgSend unmarshals", false)
	}

	equal("second message", body.Messages[1].TypeUrl, "/cosmos.gov.v1.MsgVote")
	var vote govv1.MsgVote
	if vote.Unmarshal(body.Messages[1].Value) == nil {
		equal("MsgVote proposal", vote.ProposalId, f.VoteProposal)
		equal("MsgVote voter", vote.Voter, f.Address)
		equal("MsgVote option", vote.Option, govv1.OptionNo)
		reencoded, _ := vote.Marshal()
		check("MsgVote re-encodes identically",
			bytes.Equal(reencoded, body.Messages[1].Value))
	} else {
		check("MsgVote unmarshals", false)
	}

	fmt.Println()
	if failures > 0 {
		fmt.Printf("\033[31m%d FAILED\033[0m\n", failures)
		os.Exit(1)
	}
	fmt.Println("\033[32mACCEPTED — the chain's own codec agrees with the Swift-built transaction\033[0m")
}
