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

    /// A low-order public key (all zeros) yields an all-zero shared secret:
    /// refused on encrypt and silently not ours on decrypt, as on Android.
    func testLowOrderPointIsRefused() throws {
        XCTAssertThrowsError(try NoteCipher.encrypt(note, ekPub: Data(count: 32), cm: .one))
        var ct = try NoteCipher.encrypt(note, to: owner)
        ct.replaceSubrange(0 ..< 32, with: Data(count: 32))
        XCTAssertNil(NoteCipher.tryDecryptBlind(ct, cm: .one, denom: "uerth", value: 1, ek: ek, ownerPK: owner.ownerPK))
    }
}
