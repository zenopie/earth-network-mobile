import CryptoKit
import XCTest
@testable import EarthCore

/// The value-blind note ciphertext (v2) against chain zk/privacy's golden
/// (formats_test.go goldenKeys / goldenBlindCT), with v1's golden for the same
/// keys alongside (ports BlindNoteTest.kt).
final class BlindNoteTests: XCTestCase {
    let ek = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0 ..< 32).map { UInt8($0 + 1) }))
    let esk = Data((0 ..< 32).map { UInt8(0x40 + $0) })
    lazy var owner = try! ShieldedAddress(ownerPK: PrivacyHash.ownerPK(Fr(UInt64(7))), ekPub: ek.publicKey.rawRepresentation)
    let note = NotePlaintext(denom: "uerth", value: 1_234_567, rho: Fr(UInt64(11)), rcm: Fr(UInt64(13)), memo: Data("golden memo".utf8))

    static let goldenCM = "0ad591ff4e6c10b942740693cc1cbbc471b5f6b11727f4d6252c9dcf47f59a0a"
    static let goldenV1 = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51ae3683366a28b0db80b20a9c90235b415c7e16c31a8338fd12d041aac85c3a56c2173a66046bec482b582fcfbcf49057b28fd406ae26700ffef383a1de0a2059a9cae640054df4a1945e4bf2b38e7af97b8ee62f4da48cdc145dfbc2f7708edac17b42a06c5048ef7971f7fe2dc7eb63dcc64ff592a8e7c344e0b539c6fdd1af43f0b6c741de6deb755e0d3dcce0a63c37ab0b016f6d707e41cf2e5f458ee1bcf7cdd19087661ba6d6bf670abae5881684f2a2c89ce02b0a4db"
    static let goldenV2 = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a8b8d4fe44e9fcb771cba93975cb4507ff1d20e446a6a4cd8336f9a50186a7de58a5b4570c62bfd9cd347f5921103700103da6af3ce492bbd1f936a4310b3b01a1d583847125f7632547dfb2ea23c438f21cd4a419f9ef66d92660af42686e93c890bc37f68cf282f46ca2550ab2df0ce7191a11e7721ce736e0d1bdd62af8be221017ee455ab79e7b2ea0e756a86c39910"

    func testV1MatchesTheChainsGolden() throws {
        XCTAssertEqual(Self.goldenCM, note.cm(ownerPK: owner.ownerPK).hex)
        XCTAssertEqual(Self.goldenV1, Vectors.hex(try NoteCipher.encryptWith(esk: esk, note, to: owner)))
    }

    func testV2MatchesTheChainsGolden() throws {
        let ct = try NoteCipher.encryptBlindWith(esk: esk, note, ekPub: owner.ekPub)
        XCTAssertEqual(177, ct.count)
        XCTAssertEqual(Self.goldenV2, Vectors.hex(ct))
        let cm = note.cm(ownerPK: owner.ownerPK)
        XCTAssertEqual(note, NoteCipher.tryDecryptBlind(ct, cm: cm, denom: "uerth", value: 1_234_567, ek: ek, ownerPK: owner.ownerPK))
    }

    func testV2IsBoundByTheCmCheck() throws {
        let ct = try NoteCipher.encryptBlindWith(esk: esk, note, ekPub: owner.ekPub)
        let cm = note.cm(ownerPK: owner.ownerPK)
        XCTAssertNil(NoteCipher.tryDecryptBlind(ct, cm: cm, denom: "uerth", value: 1_234_568, ek: ek, ownerPK: owner.ownerPK))
        XCTAssertNil(NoteCipher.tryDecryptBlind(ct, cm: cm, denom: "uanml", value: 1_234_567, ek: ek, ownerPK: owner.ownerPK))
        XCTAssertNil(NoteCipher.tryDecryptBlind(ct, cm: .one, denom: "uerth", value: 1_234_567, ek: ek, ownerPK: owner.ownerPK))
        let wrong = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 9, count: 32))
        XCTAssertNil(NoteCipher.tryDecryptBlind(ct, cm: cm, denom: "uerth", value: 1_234_567, ek: wrong, ownerPK: owner.ownerPK))
        var bad = ct
        bad[100] ^= 1
        XCTAssertNil(NoteCipher.tryDecryptBlind(bad, cm: cm, denom: "uerth", value: 1_234_567, ek: ek, ownerPK: owner.ownerPK))
        XCTAssertNil(NoteCipher.tryDecryptBlind(try NoteCipher.encryptWith(esk: esk, note, to: owner), cm: cm, denom: "uerth", value: 1_234_567, ek: ek, ownerPK: owner.ownerPK))
    }

    /// The wallet stake note v2 (wallet-defined, PRIVACY_FORMATS 5: 201
    /// bytes, the label inside) under the same keys, byte for byte Android's
    /// goldens (BlindNoteTest.kt, cross-checked by an independent Python
    /// decryption): an unlabelled note (the cm the chain's vectors pin) and a
    /// labelled one.
    func testWalletStakeNoteMatchesItsGolden() throws {
        let v = Vectors.obj("blind")
        XCTAssertEqual(v["wallet_stake_ciphertext_bytes"] as? String, String(NoteCipher.stakeCiphertextBytes))
        let asset = PrivacyHash.assetID(Vectors.json["derth_denom"] as! String)
        let plain = NoteCipher.StakeOpening(asset: asset, amount: 1_800_000, rho: Fr(UInt64(11)), rcm: Fr(UInt64(13)))
        let cm = plain.cm(ownerPK: owner.ownerPK)
        XCTAssertEqual(v["stake_cm_derth_1800000"] as? String, cm.hex)
        let ct = try NoteCipher.encryptStakeWith(esk: esk, plain, ekPub: owner.ekPub, cm: cm)
        XCTAssertEqual(201, ct.count)
        XCTAssertEqual(Self.goldenStakeV2, Vectors.hex(ct))
        XCTAssertEqual(plain, NoteCipher.tryDecryptStake(ct, cm: cm, ek: ek, ownerPK: owner.ownerPK))

        let labelled = plain.with(label: StakeLabel(moveKey: Fr(UInt64(0x4d4b)), moveTime: 1000, exposed: 200))
        let lcm = labelled.cm(ownerPK: owner.ownerPK)
        XCTAssertEqual(v["stake_cm_derth_1800000_labelled"] as? String, lcm.hex)
        let lct = try NoteCipher.encryptStakeWith(esk: esk, labelled, ekPub: owner.ekPub, cm: lcm)
        XCTAssertEqual(Self.goldenStakeV2Labelled, Vectors.hex(lct))
        XCTAssertEqual(labelled, NoteCipher.tryDecryptStake(lct, cm: lcm, ek: ek, ownerPK: owner.ownerPK))
        // The label is bound by the cm: the same body under the unlabelled cm opens as nothing.
        XCTAssertNil(NoteCipher.tryDecryptStake(lct, cm: cm, ek: ek, ownerPK: owner.ownerPK))
        let wrong = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: 9, count: 32))
        XCTAssertNil(NoteCipher.tryDecryptStake(ct, cm: cm, ek: wrong, ownerPK: owner.ownerPK))
        var bad = ct
        bad[150] ^= 1
        XCTAssertNil(NoteCipher.tryDecryptStake(bad, cm: cm, ek: ek, ownerPK: owner.ownerPK))
        // A v2 pool ciphertext never opens as a stake note (another length and salt).
        XCTAssertNil(NoteCipher.tryDecryptStake(try NoteCipher.encryptBlindWith(esk: esk, note, ekPub: owner.ekPub), cm: cm, ek: ek, ownerPK: owner.ownerPK))
        XCTAssertEqual(v["note_ct"] as? String, Self.goldenV2)
    }

    /// The wallet stake note v2 of (derth, 1800000, rho 11, rcm 13), unlabelled and labelled (0x4d4b, 1000, 200): Android's bytes.
    static let goldenStakeV2 = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a4acda0bd0608c8007ac2122efac709dab693cd96e83cab5ab4603f747f3c136214b4f26db58f5c9a523c7936ed9eded5dcca94061e0f2cde9565e931dbbcde137eb70d7626ff5a5f7f87ca16bf918c833c0e751a006159e638180e36d8c9390721e533f265d6c082c3b39282802bdef58ce478ee525812f7a2409ea2d7582e980be9bd239897e0a849e9bb197f09bdd3bd37309625b149d5c16b7a883c2e3573786ab371c01d82e218"
    static let goldenStakeV2Labelled = "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a524088d6a8c8bfb2c4c18834e3dca9e08f49563fb5d05b22c0fa65732779254918403cfc2bb8d36aa9c8839639732209620e6666fa89c49fcda050c3d5d4324a3e316bcad126dba984ba60ca4edde3f2e3e4dd565e9d4bd8b85195072085bef5f2454561786101b50164958e27585a23ebbbafff57047de9813082e6a96f0034a597ea4639cad38084f5cf40e3808eee9eb85a4eb63fef8621553805d590d6d5101e93f8be0b7e60e3"

    /// A low-order public key (all zeros) yields an all-zero shared secret:
    /// refused on encrypt and silently not ours on decrypt, as on Android.
    func testLowOrderPointIsRefused() throws {
        XCTAssertThrowsError(try NoteCipher.encrypt(note, ekPub: Data(count: 32), cm: .one))
        var ct = try NoteCipher.encrypt(note, to: owner)
        ct.replaceSubrange(0 ..< 32, with: Data(count: 32))
        XCTAssertNil(NoteCipher.tryDecryptBlind(ct, cm: .one, denom: "uerth", value: 1, ek: ek, ownerPK: owner.ownerPK))
    }
}
