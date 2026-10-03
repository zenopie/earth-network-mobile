import BigInt
import Foundation

/// The domain-tagged Poseidon2 derivations of the chain's zk/privacy, which
/// the Noir privacy_core recomputes in-proof. Ports `privacy/zk/Privacy.kt`;
/// every function must match its Go twin bit for bit (the golden vectors in
/// EarthCoreTests pin them to output of the chain's own code): a divergence
/// makes every proof fail, or makes the wallet miss its own notes.
public enum PrivacyHash {
    public static let tagID = tag("earth.id")
    public static let tagOwner = tag("earth.owner")
    public static let tagLeaf = tag("earth.leaf")
    public static let tagSN = tag("earth.sn")
    public static let tagPC = tag("earth.pc")
    public static let tagCM = tag("earth.cm")
    public static let tagNF = tag("earth.nf")
    public static let tagReg = tag("earth.reg")
    public static let tagAsset = tag("earth.asset")
    public static let tagSignal = tag("earth.signal")
    public static let tagBytes = tag("earth.bytes")
    public static let tagScope = tag("earth.scope")
    // The stake note tree (x/shieldedstaking, circuits/stake).
    public static let tagStake = tag("earth.stake")
    public static let tagSPC = tag("earth.spc")
    public static let tagSNF = tag("earth.snf")
    public static let tagOTag = tag("earth.otag")

    /// The fee asset, privacy_core::ASSET_ERTH.
    public static let assetErth: Fr = assetID("uerth")

    private static func tag(_ s: String) -> Fr { Fr(BigUInt(Data(s.utf8))) }

    public static func h(_ xs: Fr...) -> Fr { Poseidon2.hash(xs) }

    public static func u64(_ v: UInt64) -> Fr { Fr(v) }

    public static func idc(_ idSecret: Fr) -> Fr { h(tagID, idSecret) }

    public static func ownerPK(_ nk: Fr) -> Fr { h(tagOwner, nk) }

    public static func identityLeaf(idc: Fr, dscKey: Fr, country: Fr, activatedAt: UInt64) -> Fr {
        h(tagLeaf, idc, dscKey, country, u64(activatedAt))
    }

    /// ISO 3166-1 alpha-2 as two big-endian ASCII bytes; anything else is 0 (unknown).
    public static func countryField(_ cc: String) -> Fr {
        let b = Array(cc.utf8)
        guard b.count == 2, (65 ... 90).contains(b[0]), (65 ... 90).contains(b[1]) else { return .zero }
        return Fr(UInt64(b[0]) << 8 | UInt64(b[1]))
    }

    public static func scopeNullifier(idSecret: Fr, scope: Fr) -> Fr { h(tagSN, idSecret, scope) }

    public static func pc(ownerPK: Fr, rho: Fr, rcm: Fr) -> Fr { h(tagPC, ownerPK, rho, rcm) }

    public static func cm(asset: Fr, value: UInt64, pc: Fr) -> Fr { h(tagCM, asset, u64(value), pc) }

    /// position is a u32 in the circuit.
    public static func nf(nk: Fr, rho: Fr, position: UInt64) -> Fr {
        precondition(position <= 0xffff_ffff, "position is a u32")
        return h(tagNF, nk, rho, u64(position))
    }

    /// A stake note's hidden owner: H(TAG_SPC, owner_pk, rho, rcm).
    public static func stakePC(ownerPK: Fr, rho: Fr, rcm: Fr) -> Fr { h(tagSPC, ownerPK, rho, rcm) }

    /// A stake note: H(TAG_STAKE, AssetID(stake denom), amount, spc).
    public static func stakeCM(asset: Fr, amount: UInt64, spc: Fr) -> Fr { h(tagStake, asset, u64(amount), spc) }

    /// A stake note's nullifier: H(TAG_SNF, nk, rho, position), position a u32.
    public static func stakeNF(nk: Fr, rho: Fr, position: UInt64) -> Fr {
        precondition(position <= 0xffff_ffff, "position is a u32")
        return h(tagSNF, nk, rho, u64(position))
    }

    /// A Groundworks position's owner tag: H(TAG_OTAG, owner_pk, salt).
    public static func ownerTag(ownerPK: Fr, salt: Fr) -> Fr { h(tagOTag, ownerPK, salt) }

    public static func assetID(_ denom: String) -> Fr {
        let b = Data(denom.utf8)
        return Poseidon2.hash([tagAsset, u64(UInt64(b.count))] + chunks31(b))
    }

    /// H(TAG_BYTES, len(b), 31-byte big-endian chunks...).
    public static func bytes(_ b: Data) -> Fr {
        Poseidon2.hash([tagBytes, u64(UInt64(b.count))] + chunks31(b))
    }

    public static func bytes(_ s: String) -> Fr { bytes(Data(s.utf8)) }

    private static func chunks31(_ b: Data) -> [Fr] {
        let a = [UInt8](b)
        var out: [Fr] = []
        var i = 0
        while i < a.count {
            let j = min(i + 31, a.count)
            out.append(Fr(BigUInt(Data(a[i ..< j]))))
            i = j
        }
        return out
    }

    /// H(TAG_SIGNAL, Bytes(msg_type), Bytes(chain_id), fields...).
    public static func signal(msgType: String, chainID: String, fields: [Fr]) -> Fr {
        Poseidon2.hash([tagSignal, bytes(msgType), bytes(chainID)] + fields)
    }

    public static func scope(_ kind: String, _ args: Fr...) -> Fr {
        Poseidon2.hash([tagScope, bytes(kind)] + args)
    }

    public static func claimScope(day: UInt64) -> Fr { scope("claim", u64(day)) }
    public static func caretakerScope() -> Fr { scope("caretaker") }
    public static func referrerScope() -> Fr { scope("referrer") }
    public static func proposalScope(proposalID: UInt64, round: UInt64) -> Fr { scope("proposal", u64(proposalID), u64(round)) }
    public static func removalScope(ballotID: UInt64) -> Fr { scope("removal", u64(ballotID)) }
    public static func proposeRemovalScope(optionID: UInt64, day: UInt64) -> Fr { scope("propose_removal", u64(optionID), u64(day)) }

    /// The transparent gas grant's scope for month `yyyymm` (UTC, e.g. 202610):
    /// one grant per nullifier per month.
    public static func gasScope(yyyymm: UInt64) -> Fr { scope("gas", u64(yyyymm)) }

    public static let gasTransparentSignalType = "earth.gas.transparent"

    /// The transparent gas grant's signal, binding the paid account's raw
    /// address bytes.
    public static func gasTransparentSignal(chainID: String, address: Data) -> Fr {
        signal(msgType: gasTransparentSignalType, chainID: chainID, fields: [bytes(address)])
    }

    /// The passport proof's `address` input: H(TAG_REG, idc, pc_anml, pc_erth, affiliate).
    public static func registrationBinding(idc: Fr, pcAnml: Fr, pcErth: Fr, affiliate: Fr) -> Fr {
        h(tagReg, idc, pcAnml, pcErth, affiliate)
    }
}
