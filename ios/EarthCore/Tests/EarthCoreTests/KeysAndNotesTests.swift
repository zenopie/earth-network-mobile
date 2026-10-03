import XCTest
@testable import EarthCore

/// Ports KeysAndNotesTest.kt: the wallet's own derivations (PRIVACY_FORMATS.md),
/// pinned byte for byte to Android's values.
final class KeysAndNotesTests: XCTestCase {
    static let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let keys = try! PrivacyKeys.fromMnemonic(mnemonic)
    let other = try! PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow")

    static let knownIDSecret = "059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba"
    static let knownNK = "0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81"
    static let knownEkPub = "c6327c6004804dce1fd6a876c9c983204cb251507a5da8ae845e52cde8585773"
    static let knownAddress = "erthz1qyh7prm54w0lu9ymzm3dtpm3r3juewetjuu8675hw0gpu5hywx4ll33j03sqfqzdec0ad2rke8ycxgzvkfg4q7ja4zhgghjjeh59s4mn9gwhg2"
    static let noteCTGolden =
        "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c27a4ccf6eb32e22c660248ac5cfc37d11ce8" +
        "70fdb324e97e1e94176876b187056319e704577b33ba5fa53a834ac83f419c51cbea350859acf194fd7c69dc74074d503714" +
        "b4783349656cd5427e678cfec273deb29a786db6ca40b1b95cf5a5356bab620aa7657442f8d130c415aa7c9bfdbc70094c92" +
        "7ecc8448cd159c76fb0d937b4af129915b6eee36c1066dfd4e66ccbd1fa244c0ce319a0cc12095a58d894477a8194318471c" +
        "9d1365d3eb99e330944de6893064b50614"

    func testDerivationIsPinned() throws {
        XCTAssertEqual(Self.knownIDSecret, keys.idSecret.hex)
        XCTAssertEqual(Self.knownNK, keys.nk.hex)
        XCTAssertEqual(Self.knownEkPub, Vectors.hex(keys.ekPub))
        XCTAssertEqual(Self.knownAddress, keys.address.encode())
        XCTAssertEqual(PrivacyHash.idc(keys.idSecret), keys.idc)
        XCTAssertNotEqual(keys.idSecret, other.idSecret)
        // Stake self-mints and owner-tag salts (PRIVACY_FORMATS.md section 1),
        // cross-checked with an independent Python HMAC derivation.
        XCTAssertEqual("2e169030a56d7e472fc9f342ef18783bc65662f82edf58e00b9fa23b7131fbb3", keys.stakeMintSecrets(0).rho.hex)
        XCTAssertEqual("16443a6dc3a8058eaafa1b6feb6d1804cf71794815a830e782756c2ccf759bab", keys.stakeMintSecrets(0).rcm.hex)
        XCTAssertEqual("05708bcf1c37660a1859a735e21a57c9d802f78ab8274364eb9199583b095c32", keys.stakeMintSecrets(1).rho.hex)
        XCTAssertEqual("2e4cb7ef401c2041af61f2e4a7593f5afed0d85ab28cefc6cde17aa9c28b1b22", keys.stakeMintSecrets(1).rcm.hex)
        XCTAssertEqual("0685f54037389aaceee42288ed8c8c996a884e297ffee771c73370ca885e1618", keys.otagSalt(0).hex)
        XCTAssertEqual("2e52e73b7af259664a34df8bcee1c0476009a37e0ab2e52b285bae497845a9ba", keys.otagSalt(1).hex)
        let (r0, c0) = keys.stakeMintSecrets(0)
        XCTAssertEqual(PrivacyHash.stakePC(ownerPK: keys.ownerPK, rho: r0, rcm: c0), keys.stakeMintPC(0))
        XCTAssertEqual(PrivacyHash.ownerTag(ownerPK: keys.ownerPK, salt: keys.otagSalt(1)), keys.ownerTag(1))
    }

    func testAddressRoundTrip() throws {
        let s = keys.address.encode()
        XCTAssertTrue(s.hasPrefix("erthz1"))
        XCTAssertEqual(116, s.count)
        XCTAssertEqual(keys.address, try ShieldedAddress.decode(s))
        XCTAssertEqual(keys.address, try ShieldedAddress.decode(s.uppercased()))
        var chars = Array(s)
        chars[20] = chars[20] == "q" ? "p" : "q"
        XCTAssertThrowsError(try ShieldedAddress.decode(String(chars)))
        XCTAssertFalse(ShieldedAddress.isShielded("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"))
    }

    func testAddressRejects() throws {
        func enc(_ payload: Data, hrp: String = "erthz") -> String {
            Bech32m.encode(hrp: hrp, data: try! Bech32m.convertBits([UInt8](payload), from: 8, to: 5, pad: true))
        }
        let good = keys.address.payload
        XCTAssertEqual(keys.address, try ShieldedAddress.decode(enc(good)))
        XCTAssertThrowsError(try ShieldedAddress.decode(enc(Data([2]) + good.suffix(64))))
        XCTAssertThrowsError(try ShieldedAddress.decode(enc(good.prefix(64))))
        let p = Fr.modulus.serialize()
        XCTAssertThrowsError(try ShieldedAddress.decode(enc(Data([1]) + p + good.suffix(32))))
        XCTAssertThrowsError(try ShieldedAddress.decode(enc(good, hrp: "erth")))
    }

    func testNoteEncryption() throws {
        let denom = "derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        let n = NotePlaintext.fresh(denom, 123_456_789, memo: Data("hi".utf8))
        let cm = n.cm(ownerPK: keys.ownerPK)
        let ct = try NoteCipher.encrypt(n, to: keys.address)
        XCTAssertEqual(217, ct.count)
        XCTAssertEqual(n, NoteCipher.tryDecrypt(ct, cm: cm, keys: keys, denoms: AssetDenoms([denom])))
        let unresolved = try XCTUnwrap(NoteCipher.tryDecrypt(ct, cm: cm, keys: keys))
        XCTAssertEqual("asset/" + PrivacyHash.assetID(denom).hex, unresolved.denom)
        XCTAssertEqual(cm, unresolved.cm(ownerPK: keys.ownerPK))
        XCTAssertNil(NoteCipher.tryDecrypt(ct, cm: cm, keys: other))
        XCTAssertNil(NoteCipher.tryDecrypt(ct, cm: PrivacyHash.cm(asset: n.asset, value: n.value + 1, pc: n.pc(ownerPK: keys.ownerPK)), keys: keys))
        for i in [0, 31, 32, 40, ct.count - 1] {
            var t = ct
            t[i] ^= 1
            XCTAssertNil(NoteCipher.tryDecrypt(t, cm: cm, keys: keys))
        }
        XCTAssertEqual(ct.count, NoteCipher.dummy().count)
    }

    func testNoteCiphertextGolden() throws {
        let n = NotePlaintext(denom: "uanml", value: 1_000_000, rho: Vectors.fe(7), rcm: Vectors.fe(8), memo: Data("memo".utf8))
        let esk = Data((0 ..< 32).map { UInt8($0 + 1) })
        let ct = try NoteCipher.encryptWith(esk: esk, n, to: keys.address)
        XCTAssertEqual(Self.noteCTGolden, Vectors.hex(ct))
        XCTAssertEqual(n, NoteCipher.tryDecrypt(ct, cm: n.cm(ownerPK: keys.ownerPK), keys: keys))
    }

    func testSelfMintsAreDeterministic() throws {
        let (rho, rcm) = keys.mintSecrets(0)
        XCTAssertEqual(keys.mintSecrets(0).rho, rho)
        XCTAssertEqual(PrivacyHash.pc(ownerPK: keys.ownerPK, rho: rho, rcm: rcm), keys.mintPC(0))
        XCTAssertNotEqual(keys.mintPC(0), keys.mintPC(1))
        XCTAssertEqual(keys.mintPC(3), try PrivacyKeys.fromMnemonic(Self.mnemonic).mintPC(3))
    }

    /// Stake ciphertext v3 (PRIVACY_FORMATS.md section 3) against an
    /// independent Python (cryptography) encryption: esk = 01..20 to this
    /// wallet's ek, cm the note golden's (encryption binds it, decryption
    /// recomputes it).
    func testStakeCiphertextGoldenAndRoundTrip() throws {
        let asset = PrivacyHash.assetID("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        let o = NoteCipher.StakeOpening(asset: asset, amount: 1_800_000, rho: Fr(UInt64(0x11)), rcm: Fr(UInt64(0x13)))
        let cm = Vectors.fr("05e80ddba92b607efc03967707d42b7cbc814f5767040a9066902fb53b3ff5a3")
        let ct = try NoteCipher.encryptStakeWith(esk: Data((1 ... 32).map { UInt8($0) }), o, ekPub: keys.ekPub, cm: cm)
        XCTAssertEqual(NoteCipher.stakeCiphertextBytes, ct.count)
        XCTAssertEqual(
            "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7cdcf30ca4a0dc1e2eb74b496a798822e18fe8843476dd456581afcd738b87c761" +
                "00d7d974c63d1b9a2e1aa3b1967d97ef2b947a74ccf6ed19134c3f56f8cf8cac78e97f15145a9447c8f7eb0bef62efd4ef0c5ce5f6543f61cd3cde5d60ca8697" +
                "368a1b5329eebc27dcb4d3f4763016bb034210a3a91ea7954a",
            Vectors.hex(ct)
        )
        // The golden's cm is not this opening's, so the recipient's check refuses it.
        XCTAssertNil(NoteCipher.tryDecryptStake(ct, cm: cm, keys: keys))
        // A real stake note opens for its owner only, and only under its own cm.
        let realCM = PrivacyHash.stakeCM(asset: asset, amount: o.amount, spc: PrivacyHash.stakePC(ownerPK: keys.ownerPK, rho: o.rho, rcm: o.rcm))
        let real = try NoteCipher.encryptStake(o, ekPub: keys.ekPub, cm: realCM)
        XCTAssertEqual(o, NoteCipher.tryDecryptStake(real, cm: realCM, keys: keys))
        XCTAssertNil(NoteCipher.tryDecryptStake(real, cm: realCM, keys: other))
        XCTAssertNil(NoteCipher.tryDecryptStake(real, cm: cm, keys: keys))
        // Neither of the pool's decryptors takes a stake ciphertext.
        XCTAssertNil(NoteCipher.tryDecrypt(real, cm: realCM, keys: keys))
    }
}
