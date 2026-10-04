import BigInt
import Foundation

/// One stake proof laid out (circuits/stake). Ports `privacy/tx/StakePlan.kt`:
/// up to two of this wallet's stake notes spent under `anchor`, up to two
/// created back to it, value `vOut` leaving the notes to the chain, the stake
/// pc the chain may mint to (`mint`) and the owner tag's salt. Everything the
/// sighash binds (the StakeFields) is final once built.
///
/// `denom` is the msg's stake denom (derth/<valoper>), nil for a
/// position's update, unlock or vote,
/// whose public asset is 0.
public struct StakePlan: Sendable {
    /// A created stake note: its amount, secrets and ciphertext (to ourselves).
    public struct Out: Sendable {
        public let amount: UInt64
        public let rho: Fr
        public let rcm: Fr
        public let ciphertext: Data
    }

    private let nk: Fr
    public let denom: String?
    public let spends: [OwnedStakeNote]
    private let paths: [[Fr]]
    public let outputs: [Out]
    public let mint: (rho: Fr, rcm: Fr)
    public let tagSalt: Fr
    public let anchor: Fr
    public let vOut: UInt64
    /// The blind stake ciphertext of `mint`'s note: for a msg that mints one, empty otherwise.
    public let mintCiphertext: Data
    public let asset: Fr
    private let dummyIn: [(Fr, Fr)]
    private let dummyOut: [(Fr, Fr)]

    public init(nk: Fr, denom: String?, spends: [OwnedStakeNote], paths: [[Fr]], outputs: [Out], mint: (rho: Fr, rcm: Fr), tagSalt: Fr,
                anchor: Fr, vOut: UInt64, mintCiphertext: Data = Data()) throws {
        try require(spends.count <= 2 && outputs.count <= 2 && paths.count == spends.count, "a stake proof spends and creates at most two notes")
        try require(spends.allSatisfy { $0.denom == denom && $0.amount > 0 }, "a stake proof spends notes of its own denom")
        try require(outputs.allSatisfy { $0.amount > 0 }, "a created stake note is positive")
        let ins = spends.reduce(BigUInt(0)) { $0 + BigUInt($1.amount) }
        let outs = outputs.reduce(BigUInt(vOut)) { $0 + BigUInt($1.amount) }
        try require(ins == outs, "stake amounts do not balance: in \(ins), out \(outs)")
        self.nk = nk; self.denom = denom; self.spends = spends; self.paths = paths; self.outputs = outputs
        self.mint = mint; self.tagSalt = tagSalt; self.anchor = anchor; self.vOut = vOut
        self.mintCiphertext = mintCiphertext
        asset = denom.map(PrivacyHash.assetID) ?? .zero
        dummyIn = (0 ..< 2).map { _ in (NotePlaintext.randomField(), NotePlaintext.randomField()) }
        dummyOut = (0 ..< 2).map { _ in (NotePlaintext.randomField(), NotePlaintext.randomField()) }
    }

    public func witness(sighash: Fr) throws -> StakeWitness {
        func s<T>(_ i: Int, _ f: (OwnedStakeNote) -> T, _ d: T) -> T { i < spends.count ? f(spends[i]) : d }
        func o<T>(_ i: Int, _ f: (Out) -> T, _ d: T) -> T { i < outputs.count ? f(outputs[i]) : d }
        let idx = [0, 1]
        return try StakeWitness(
            nk: nk,
            inAmount: idx.map { s($0, \.amount, 0) },
            inRho: idx.map { s($0, \.rho, dummyIn[$0].0) },
            inRcm: idx.map { s($0, \.rcm, dummyIn[$0].1) },
            inPos: idx.map { s($0, \.position, 0) },
            inPath: idx.map { $0 < paths.count ? paths[$0] : Array(repeating: .zero, count: Merkle.depth) },
            outAmount: idx.map { o($0, \.amount, 0) },
            outRho: idx.map { o($0, \.rho, dummyOut[$0].0) },
            outRcm: idx.map { o($0, \.rcm, dummyOut[$0].1) },
            mintRho: mint.rho, mintRcm: mint.rcm, tagSalt: tagSalt,
            anchor: anchor, asset: asset, vIn: 0, vOut: vOut, sighash: sighash
        )
    }

    /// The msg's StakeProof, with `proof` (a placeholder before proving).
    public func proto(proof: Data) throws -> StakeProof {
        let w = try witness(sighash: .zero) // every public value but the sighash
        return StakeProof(proof: proof, anchor: anchor.bytes, nullifiers: w.nullifiers.map(\.bytes), commitments: w.commitments.map(\.bytes),
                          ciphertexts: [0, 1].map { $0 < outputs.count ? outputs[$0].ciphertext : Data() },
                          spcMint: w.spcMint.bytes, ownerTag: w.otag.bytes, spcCiphertext: mintCiphertext)
    }

    /// A created stake note of `amount` `denom` back to `keys`, with its stake ciphertext.
    public static func out(_ keys: PrivacyKeys, denom: String, amount: UInt64) throws -> Out {
        let rho = NotePlaintext.randomField()
        let rcm = NotePlaintext.randomField()
        let asset = PrivacyHash.assetID(denom)
        let cm = PrivacyHash.stakeCM(asset: asset, amount: amount, spc: PrivacyHash.stakePC(ownerPK: keys.ownerPK, rho: rho, rcm: rcm))
        return Out(amount: amount, rho: rho, rcm: rcm,
                   ciphertext: try NoteCipher.encryptStake(.init(asset: asset, amount: amount, rho: rho, rcm: rcm), ekPub: keys.ekPub, cm: cm))
    }

    /// A stake note the chain will mint to us (spc_mint): fresh rho and rcm,
    /// and their blind stake ciphertext to our own address, which sync opens
    /// against the denom and amount the chain publishes.
    public static func selfMint(_ keys: PrivacyKeys, memo: Data = Data()) throws -> SelfMint {
        let rho = NotePlaintext.randomField()
        let rcm = NotePlaintext.randomField()
        return SelfMint(rho: rho, rcm: rcm, ciphertext: try NoteCipher.encryptBlindStake(rho: rho, rcm: rcm, ekPub: keys.ekPub, memo: memo))
    }

    public struct SelfMint: Sendable {
        public let rho: Fr
        public let rcm: Fr
        public let ciphertext: Data
    }

    /// Secrets for a spc_mint or owner tag the msg does not use: fresh, so
    /// the public value links to nothing (the circuit still proves it).
    public static func throwaway() -> (rho: Fr, rcm: Fr) { (NotePlaintext.randomField(), NotePlaintext.randomField()) }
}
