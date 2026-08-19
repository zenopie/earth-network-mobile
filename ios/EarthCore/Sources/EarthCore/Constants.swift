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
    public static let bech32Prefix = "earth"
    /// Cosmos default. Not a custom coin type — do not "fix" this to 529.
    public static let coinType: UInt32 = 118
    public static let gasDenom = "uerth"
    public static let personhoodDenom = "uanml"
    /// Both denominations are 6dp.
    public static let denomExponent = 6

    public static let msgRegisterTypeURL = "/earth.personhood.v1.MsgRegister"

    /// BIP-44 path the Android wallet derives at, and therefore the only path
    /// that reproduces an existing user's address.
    public static let derivationPath = "m/44'/118'/0'/0/0"
}
