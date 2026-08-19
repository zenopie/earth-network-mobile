module chainverify

go 1.25.3

require github.com/burnt-labs/barretenberg-go v0.0.0

// Sibling checkout of earth-network-chain, which vendors barretenberg-go
// pinned to aztec_tag v5.0.0 — the same bb build the chain verifies with.
replace github.com/burnt-labs/barretenberg-go => ../../../earth-network-chain/third_party/barretenberg-go
