import Foundation

/// Endpoints and chain identity, mirroring `android/.../Constants.kt`.
/// That file is canonical; if the two disagree it is this one that is wrong.
public enum Constants {
    /// Passport proof verification and app update metadata.
    public static let backendBaseURL = URL(string: "https://api.erth.network")!

    /// LCD/REST base. The node sits behind a Cloudflare Tunnel that terminates
    /// TLS at the edge, which is why this is plain HTTPS with no port even
    /// though the node moves between Akash leases.
    public static let lcdURL = URL(string: "https://lcd.erth.network")!

    /// CometBFT RPC. Only the explorer needs it, and only for the one thing the
    /// LCD cannot do: a *range* of blocks in one request.
    public static let rpcURL = URL(string: "https://rpc.erth.network")!

    public static let chainID = "earth-1"

    // ======================== SET AT THE GENESIS CEREMONY ========================
    /// sha256 of the live earth-1's genesis as a CometBFT node serves it: the
    /// base64 chunks of the RPC's /genesis_chunked, decoded and concatenated.
    /// earth-1 has been relaunched under the same chain id, so the chain id
    /// alone does not tell the live chain from an old genesis or a fork:
    /// Settings → Network refuses a node whose genesis is not this one
    /// (`NodeSettings.probe`). The genesis is what every node keeps,
    /// state-synced or pruned, where block 1 is not.
    ///
    /// NOT the sha256 of genesis.json (deploy's akash/genesis.sha256):
    /// CometBFT v0.38 serves its own re-encoding of the genesis doc (compact,
    /// app_name and app_version dropped), so the bytes differ from the file's.
    /// Compute it from the ceremony's genesis.json with tools/genesishash, or
    /// from any node started on it. The value below is for the chain repo's
    /// genesis at v1.1.0 (file sha256 26b396b4...), the launch genesis.
    /// Must equal Android's EARTH_GENESIS_SHA256.
    public static let genesisSHA256 = "33766415170fcd011963c481f4442e4b09aef29cd3bee229b66ac9cb2243882c"
    // =============================================================================
    public static let bech32Prefix = "earth"
    /// Cosmos default. Not a custom coin type — do not "fix" this to 529.
    public static let coinType: UInt32 = 118
    public static let gasDenom = "uerth"
    /// Both denominations are 6dp.
    public static let denomExponent = 6

    /// BIP-44 path the Android wallet derives at, and therefore the only path
    /// that reproduces an existing user's address.
    public static let derivationPath = "m/44'/118'/0'/0/0"
}
