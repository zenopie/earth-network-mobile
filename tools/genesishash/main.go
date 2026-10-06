// genesishash prints the sha256 the wallets pin for the live genesis
// (Android Constants.EARTH_GENESIS_SHA256, iOS Constants.genesisSHA256):
// the hash of the bytes a CometBFT node serves from /genesis_chunked, which
// is what Settings -> Network compares a user's node against.
//
// Those bytes are not genesis.json's. earthd loads the file as an SDK
// AppGenesis, converts it to CometBFT's GenesisDoc, and CometBFT v0.38
// serves its own JSON encoding of that (compact, app_name and app_version
// dropped). So the file's sha256 (deploy's akash/genesis.sha256) is printed
// beside it, and only the second line goes into the wallets.
//
//	cd tools/genesishash && go run . <genesis.json>
//
// The same value from a running node, for a genesis that fits one chunk:
//
//	curl -s $RPC/genesis_chunked | jq -r .result.data | base64 -d | shasum -a 256
package main

import (
	"crypto/sha256"
	"fmt"
	"os"

	cmtjson "github.com/cometbft/cometbft/libs/json"
	genutiltypes "github.com/cosmos/cosmos-sdk/x/genutil/types"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: genesishash <genesis.json>")
		os.Exit(2)
	}
	raw, err := os.ReadFile(os.Args[1])
	if err != nil {
		fail(err)
	}
	// The path earthd start takes (server.getGenDocProvider), then the
	// encoding rpc/core.InitGenesisChunks serves.
	ag, err := genutiltypes.AppGenesisFromFile(os.Args[1])
	if err != nil {
		fail(err)
	}
	doc, err := ag.ToGenesisDoc()
	if err != nil {
		fail(err)
	}
	served, err := cmtjson.Marshal(doc)
	if err != nil {
		fail(err)
	}
	fmt.Printf("file    %x  (akash/genesis.sha256)\n", sha256.Sum256(raw))
	fmt.Printf("served  %x  (the wallets' pin)\n", sha256.Sum256(served))
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
