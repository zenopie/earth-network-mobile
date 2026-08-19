// keycheck prints the earth address a mnemonic derives to, using the same
// cosmos-sdk code path the chain itself uses. It exists to give the Swift
// port a ground truth to test against — see ios/EarthCore/Sources/corecheck.
package main

import (
	"encoding/hex"
	"fmt"
	"os"

	"github.com/cosmos/cosmos-sdk/crypto/hd"
	"github.com/cosmos/cosmos-sdk/crypto/keys/secp256k1"
	"github.com/cosmos/cosmos-sdk/types/bech32"
	bip39 "github.com/cosmos/go-bip39"
)

func main() {
	mnemonic := "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
	if len(os.Args) > 1 {
		mnemonic = os.Args[1]
	}

	seed := bip39.NewSeed(mnemonic, "")
	master, ch := hd.ComputeMastersFromSeed(seed)
	priv, err := hd.DerivePrivateKeyForPath(master, ch, "m/44'/118'/0'/0/0")
	if err != nil {
		panic(err)
	}
	key := &secp256k1.PrivKey{Key: priv}
	addr, err := bech32.ConvertAndEncode("earth", key.PubKey().Address().Bytes())
	if err != nil {
		panic(err)
	}

	fmt.Printf("seed    %s\n", hex.EncodeToString(seed))
	fmt.Printf("priv    %s\n", hex.EncodeToString(priv))
	fmt.Printf("pub     %s\n", hex.EncodeToString(key.PubKey().Bytes()))
	fmt.Printf("address %s\n", addr)
}
