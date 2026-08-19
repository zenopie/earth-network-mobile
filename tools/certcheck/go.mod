module certcheck

go 1.25.10

require github.com/earth-network/earth v0.0.0

require (
	github.com/bits-and-blooms/bitset v1.24.4 // indirect
	github.com/consensys/gnark-crypto v0.20.1 // indirect
	golang.org/x/crypto v0.54.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
)

// Sibling checkout of the chain. Its x/pki/certs package is the definition of
// a canonical public key; there is no second copy to compare against.
replace github.com/earth-network/earth => ../../../earth-network-chain
