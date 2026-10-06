import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// The wallet's private store on disk: deleted with the wallet, a corrupt or
/// unsavable state an error rather than an empty wallet, excluded from
/// backups, one instance per directory, and what a reset keeps.
final class PrivacyStoreTests: PrivacyTestCase {
    func testForgettingAWalletDeletesItsPrivateData() async throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let chain = FakeChain()
        let w = try wallet(chain, store: try PrivacyStore.open(root: root, walletID: "w1", key: testDataKey))
        try funded(chain, w)
        try await w.sync()
        try PrivacyStore.open(root: root, walletID: "w2", key: testDataKey).save()
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w1/state.json").path))
        try PrivacyStore.delete(root: root, walletID: "w1")
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w1").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w2").path))
        try PrivacyStore.delete(root: root)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy").path))
    }

    func testACorruptStateIsAnErrorNotAnEmptyWallet() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        try PrivacyStore.open(root: root, walletID: "w", key: testDataKey).save()
        try Data("{\"notes\": [".utf8).write(to: root.appendingPathComponent("privacy/w/state.json"))
        XCTAssertThrowsError(try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)) { XCTAssertTrue($0 is PrivacyStore.CorruptState) }
    }

    func testASaveThatFailsThrows() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let s = try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)
        try s.save()
        // The directory goes read-only: the atomic write cannot place its temp file.
        let d = root.appendingPathComponent("privacy/w")
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: d.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: d.path) }
        XCTAssertThrowsError(try s.save()) { XCTAssertTrue($0 is PrivacyStore.SaveFailed) }
    }

    /// The privacy store's directory is excluded from iCloud and iTunes/Finder backups.
    func testTheStoreIsExcludedFromBackup() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("earth-backup-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let store = try PrivacyStore.open(root: root, walletID: "w1", key: testDataKey)
        store.mutate { $0.chainID = "earth-1" }
        try store.save()
        for dir in [root.appendingPathComponent("privacy"), root.appendingPathComponent("privacy/w1")] {
            var u = dir
            u.removeAllCachedResourceValues()
            let v = try u.resourceValues(forKeys: [.isExcludedFromBackupKey])
            XCTAssertEqual(true, v.isExcludedFromBackup, dir.path)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("privacy/w1/state.json").path))
        // The app's root itself is untouched (only the private data is excluded).
        var r = root
        r.removeAllCachedResourceValues()
        XCTAssertNotEqual(true, try r.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup)
    }

    func testTheProcessHoldsOneStorePerWalletDirectory() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("a6-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let s1 = try PrivacyStore.shared(root: dir, walletID: "w", key: testDataKey)
        XCTAssertTrue(s1 === (try PrivacyStore.shared(root: dir, walletID: "w", key: testDataKey)))
        s1.mutate { $0.pendingUnbonds.append(PendingUnbond(txHash: "AB", validator: "v", derth: 42, pc: .one, startedAt: 1, until: 9)) }
        try s1.save()
        XCTAssertEqual(42, try PrivacyStore.shared(root: dir, walletID: "w", key: testDataKey).state.pendingUnbonds.first?.derth)
        try PrivacyStore.delete(root: dir, walletID: "w")
        XCTAssertFalse(s1 === (try PrivacyStore.shared(root: dir, walletID: "w", key: testDataKey)))
    }

    /// A reset keeps only a verified identity.
    func testAResetDropsAnUnverifiedIdentity() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try await registered(chain, w)
        let id = try XCTUnwrap(w.store.state.identity)
        XCTAssertTrue(id.verified)
        try w.store.reset(chainID: chain.chainID)
        XCTAssertEqual(id, w.store.state.identity)
        w.store.mutate { $0.identity?.verified = false }
        try w.store.reset(chainID: chain.chainID)
        XCTAssertNil(w.store.state.identity)
        _ = try await w.sync()
        XCTAssertEqual(.live, w.identityStatus())
        XCTAssertEqual(id.activatedAt, w.store.state.identity?.activatedAt)
        XCTAssertEqual(true, w.store.state.identity?.verified)
    }

    func testStateIsSealedOnDisk() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let s = try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)
        s.mutate { $0.chainID = "earth-1"; $0.handle = "alice" }
        try s.save()
        let raw = String(decoding: try Data(contentsOf: root.appendingPathComponent("privacy/w/state.json")), as: UTF8.self)
        XCTAssertFalse(raw.contains("earth-1"))
        XCTAssertFalse(raw.contains("alice"))
        XCTAssertTrue(raw.contains("\"sealed\""))
        XCTAssertEqual("alice", try PrivacyStore.open(root: root, walletID: "w", key: testDataKey).state.handle)
    }

    func testPlaintextFromBeforeSealingIsReadAndSealed() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let d = root.appendingPathComponent("privacy/w")
        try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        var legacy = PrivacyState()
        legacy.handle = "bob"
        try JSONEncoder().encode(legacy).write(to: d.appendingPathComponent("state.json"))
        XCTAssertEqual("bob", try PrivacyStore.open(root: root, walletID: "w", key: testDataKey).state.handle)
        XCTAssertFalse(String(decoding: try Data(contentsOf: d.appendingPathComponent("state.json")), as: UTF8.self).contains("bob"))
        XCTAssertEqual("bob", try PrivacyStore.open(root: root, walletID: "w", key: testDataKey).state.handle)
    }

    func testAnotherInstallsSealedStateIsDropped() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let s = try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)
        s.mutate { $0.handle = "carol" }
        try s.save()
        let other = Data(repeating: 7, count: 32)
        XCTAssertEqual("", try PrivacyStore.open(root: root, walletID: "w", key: other).state.handle)
        XCTAssertEqual("", try PrivacyStore.open(root: root, walletID: "w", key: other).state.handle)
    }

    func testADamagedOrMovedSealIsAnError() throws {
        let root = try tmp()
        defer { try? FileManager.default.removeItem(at: root) }
        let s = try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)
        s.mutate { $0.handle = "dave" }
        try s.save()
        let f = root.appendingPathComponent("privacy/w/state.json")
        // Another wallet's directory: the wallet id is bound in.
        let v = root.appendingPathComponent("privacy/v")
        try FileManager.default.createDirectory(at: v, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: f, to: v.appendingPathComponent("state.json"))
        XCTAssertThrowsError(try PrivacyStore.open(root: root, walletID: "v", key: testDataKey)) { XCTAssertTrue($0 is PrivacyStore.CorruptState) }
        // A flipped ciphertext byte.
        var j = try JSONSerialization.jsonObject(with: Data(contentsOf: f)) as! [String: Any]
        let ct = j["ct"] as! String
        j["ct"] = (ct.first == "0" ? "1" : "0") + ct.dropFirst()
        try JSONSerialization.data(withJSONObject: j).write(to: f)
        XCTAssertThrowsError(try PrivacyStore.open(root: root, walletID: "w", key: testDataKey)) { XCTAssertTrue($0 is PrivacyStore.CorruptState) }
    }

    /// The Android seal opens here: the envelope and its AAD are one format.
    func testTheSealMatchesAndroidsLayout() throws {
        let sealed = try StateSeal.seal(Data("{}".utf8), key: testDataKey, walletID: "w")
        let j = try JSONSerialization.jsonObject(with: sealed) as! [String: Any]
        XCTAssertEqual(1, j["sealed"] as? Int)
        XCTAssertEqual("AES-256-GCM", j["alg"] as? String)
        XCTAssertEqual(24, (j["nonce"] as? String)?.count)
        XCTAssertEqual((2 + 16) * 2, (j["ct"] as? String)?.count)
        XCTAssertEqual(StateSeal.kid(testDataKey), j["kid"] as? String)
    }
}
