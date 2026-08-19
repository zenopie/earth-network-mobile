import CryptoKit
import EarthCore
import Foundation

func checkCrypto() {
    Check.group("wordlist")
    // The BIP-39 English list is fixed forever; this is its published digest.
    let joined = BIP39Wordlist.english.joined(separator: "\n") + "\n"
    Check.equal("2048 words", BIP39Wordlist.english.count, 2048)
    Check.equal(
        "sha256 matches the canonical list",
        Data(SHA256.hash(data: Data(joined.utf8))).hexString,
        "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"
    )

    Check.group("ripemd160")
    // Vectors from the RIPEMD-160 reference paper.
    Check.equal(
        "empty string",
        RIPEMD160.hash(Data()).hexString,
        "9c1185a5c5e9fc54612808977ee8f548b2258d31"
    )
    Check.equal(
        "\"abc\"",
        RIPEMD160.hash(Data("abc".utf8)).hexString,
        "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"
    )
    Check.equal(
        "\"message digest\"",
        RIPEMD160.hash(Data("message digest".utf8)).hexString,
        "5d0689ef49d2fae572b881b123a85ffa21595f36"
    )
    Check.equal(
        "a million a's",
        RIPEMD160.hash(Data(String(repeating: "a", count: 1_000_000).utf8)).hexString,
        "52783243c1697bdbe16d37f97f68f08325dc1528"
    )

    Check.group("bech32")
    // BIP-173's own valid vectors, round-tripped.
    for vector in ["A12UEL5L", "abcdef1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw"] {
        let decoded = try? Bech32.decode(vector)
        Check.that("decodes \(vector)", decoded != nil)
        if let decoded, let re = try? Bech32.encode(
            hrp: decoded.hrp,
            data: try Bech32.convertBits([UInt8](decoded.data), from: 8, to: 5, pad: true)
        ) {
            // Re-encoding only round-trips when the payload is a whole number
            // of bytes, which is why this is asserted on the byte payload
            // rather than the raw 5-bit words.
            Check.equal("round-trips \(vector)", re, vector.lowercased())
        }
    }
    Check.throwsError("rejects a flipped character") {
        _ = try Bech32.decode("A12UEL5M")
    }
    Check.throwsError("rejects mixed case") {
        _ = try Bech32.decode("A12uel5l")
    }

    Check.group("bip39")
    // Trezor's vectors: all-zero entropy, and the seed under passphrase TREZOR.
    let zeroEntropy = Data(repeating: 0, count: 16)
    let abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    Check.equal("zero entropy -> phrase", try! BIP39.mnemonic(fromEntropy: zeroEntropy), abandon)
    Check.equal("phrase -> zero entropy", try! BIP39.entropy(fromMnemonic: abandon).hexString, zeroEntropy.hexString)
    Check.equal(
        "seed under passphrase TREZOR",
        BIP39.seed(fromMnemonic: abandon, passphrase: "TREZOR").hexString,
        "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
    )
    Check.that("rejects a bad checksum", !BIP39.isValid(mnemonic:
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon"))
    Check.that("rejects an unknown word", !BIP39.isValid(mnemonic:
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zzzz"))
    let generated = try! BIP39.generateMnemonic()
    Check.equal("generates 12 words", generated.split(separator: " ").count, 12)
    Check.that("generated phrase validates", BIP39.isValid(mnemonic: generated))

    Check.group("earth key derivation")
    // Every figure below is what `go run ./tools/keycheck` prints — that tool
    // derives through cosmos-sdk's own hd package, so this asserts agreement
    // with the chain rather than with itself.
    let key = try! EarthKey(mnemonic: abandon)
    Check.equal(
        "seed (empty passphrase)",
        BIP39.seed(fromMnemonic: abandon).hexString,
        "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4"
    )
    Check.equal(
        "private key at m/44'/118'/0'/0/0",
        key.privateKey.hexString,
        "c4a48e2fce1481cd3294b4490f6678090ea98d3d0e5cd984558ab0968741b104"
    )
    Check.equal(
        "compressed public key",
        key.publicKey.hexString,
        "024f4e2ad99c34d60b9ba6283c9431a8418af8673212961f97a77b6377fcd05b62"
    )
    Check.equal("earth address", key.address, "earth19rl4cm2hmr8afy4kldpxz3fka4jguq0a8wkm3u")
    Check.that("its own address validates", EarthKey.isValidAddress(key.address))
    Check.that("rejects a cosmos-prefixed address",
               !EarthKey.isValidAddress("cosmos19rl4cm2hmr8afy4kldpxz3fka4jguq0auqdal4"))
    Check.that("rejects a corrupted address",
               !EarthKey.isValidAddress("earth19rl4cm2hmr8afy4kldpxz3fka4jguq0a8wkm3v"))

    Check.group("signing")
    let message = Data(SHA256.hash(data: Data("earth-1".utf8)))
    let signature = try! key.sign(message)
    Check.equal("signature is 64 raw bytes", signature.count, 64)
    // Deterministic per RFC 6979, so the same message must sign identically.
    Check.equal("deterministic", try! key.sign(message).hexString, signature.hexString)
}
