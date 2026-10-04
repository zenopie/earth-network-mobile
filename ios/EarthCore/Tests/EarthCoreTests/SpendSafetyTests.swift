import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// What a private tx may spend and when its notes come back: a timeout
/// height on every tx, spends marked pending before the broadcast answers
/// and released only on the chain's word, a tip far past the verified sync
/// refused, an anchor about to lapse replaced, no unshield to a module account.
final class SpendSafetyTests: PrivacyTestCase {
    func testPrivateTxsCarryATimeoutAndPendingWaitsForIt() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try await w.sync()
        let tip = try await chain.tipHeight()
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
        XCTAssertEqual(tip + PrivateTxEngine.timeoutBlocks, chain.lastTimeoutHeight)
        try await w.sync()
        chain.dropNext = 1
        let t = try await chain.tipHeight() + PrivateTxEngine.timeoutBlocks
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        XCTAssertEqual(t, w.notes.first { $0.unspent }?.pendingUntil)
        chain.now += 24 * 3600
        while try await chain.tipHeight() < t { chain.emptyBlock() }
        try await w.sync()
        XCTAssertNotNil(w.notes.first { $0.unspent }?.pendingAt)
        chain.emptyBlock()
        try await w.sync()
        XCTAssertNil(w.notes.first { $0.unspent }?.pendingAt)
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
    }

    func testSpendsArePendingBeforeTheBroadcastAnswers() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, privateChain: LostAnswerChain(chain))
        try funded(chain, a)
        _ = try await a.sync()
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) }) { $0 is URLError }
        let n = try XCTUnwrap(a.store.state.notes.first { $0.note.value == 1_000_000 })
        XCTAssertNotNil(n.pendingAt)
        XCTAssertEqual(chain.txs.keys.first, n.pendingTx)
    }

    func testARefusedBroadcastUnmarks() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        try funded(chain, a)
        _ = try await a.sync()
        chain.rejectNext = 1
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) }) { $0 is UnsignedTx.TxRejected }
        XCTAssertTrue(a.store.state.notes.allSatisfy { $0.pendingAt == nil })
    }

    func testPendingIsReleasedOnlyOnTheChainsWord() async throws {
        let chain = FakeChain()
        let idx = WrappedIndexer(chain)
        let a = try wallet(chain, indexer: idx)
        try funded(chain, a)
        _ = try await a.sync()
        // Dropped from the mempool: missing once past its timeout, released.
        chain.dropNext = 1
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        while try await chain.tipHeight() <= chain.lastTimeoutHeight { chain.emptyBlock() }
        let r1 = try await a.sync()
        XCTAssertTrue(r1.verified)
        XCTAssertTrue(a.store.state.notes.allSatisfy { $0.pendingAt == nil })
        // Committed, its spend hidden by the indexer: kept pending, the sync unverified.
        let hide = a.store.state.notes[0].nf
        idx.nullifiersOverride = { p in HeightPage(blocks: p.blocks.map { (height: $0.height, items: $0.items.filter { $0 != hide }) },
                                                   nextHeight: p.nextHeight, complete: p.complete, syncedHeight: p.syncedHeight) }
        chain.unconfirmedNext = 1
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) })
        while try await chain.tipHeight() <= chain.lastTimeoutHeight { chain.emptyBlock() }
        let r2 = try await a.sync()
        XCTAssertFalse(r2.verified)
        XCTAssertTrue(a.snapshot.rootsError?.contains("did not report its spend") == true)
        XCTAssertNotNil(a.store.state.notes.first { $0.nf == hide }?.pendingAt)
        chain.txLookupBlind = true
        _ = try await a.sync()
        XCTAssertNotNil(a.store.state.notes.first { $0.nf == hide }?.pendingAt)
    }

    func testATipFarPastTheVerifiedHeightIsRefusedBeforeAnythingIsSent() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try mintTo(chain, a, "uerth", 500_000)
        try await a.sync()
        let sent = chain.txs.count
        chain.sendTipAhead = 1_000_000_000_000
        do {
            _ = try await a.send(to: b.keys.address, denom: "uerth", amount: 1_000)
            XCTFail("sent on an inflated tip")
        } catch let e as PrivacyError {
            XCTAssertTrue(e.description.contains("far past"), e.description)
        }
        XCTAssertEqual(sent, chain.txs.count)
        XCTAssertTrue(a.notes.allSatisfy { $0.pendingAt == nil })
        chain.sendTipAhead = 0
        _ = try await a.send(to: b.keys.address, denom: "uerth", amount: 1_000)
        let tip = try await chain.tipHeight()
        XCTAssertTrue(PrivateTxEngine.tipSane(tip, verified: a.store.state.verifiedHeight))
    }

    func testAnOutsizedPendingTimeoutIsSettledByTheTxStatus() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try mintTo(chain, a, "uerth", 500_000)
        try await a.sync()
        // A mark made against an inflated tip (by an earlier version), for a tx the node never relayed.
        a.store.mutate {
            $0.notes[0].pendingAt = chain.now; $0.notes[0].pendingUntil = 1_000_000_000_000_050; $0.notes[0].pendingTx = String(repeating: "AB", count: 32)
        }
        try await a.sync()
        XCTAssertNotNil(a.store.state.notes[0].pendingAt)
        chain.now += WalletSync.pendingTimeout + 1
        try await a.sync()
        XCTAssertNil(a.store.state.notes[0].pendingAt)
        XCTAssertFalse(PrivateTxEngine.timeoutSane(1_000_000_000_000_050, verifiedNow: a.store.state.verifiedHeight))
        XCTAssertTrue(PrivateTxEngine.timeoutSane(a.store.state.verifiedHeight + PrivateTxEngine.timeoutBlocks, verifiedNow: a.store.state.verifiedHeight))
    }

    func testAnAnchorAboutToLapseIsReplacedOrRefused() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice), b = try wallet(chain, bob)
        let n = try NoteOut.mintToSelf(a.keys, denom: "uerth")
        chain.shield("uerth", 3_000_000, n.pc, n.ciphertext)
        try await a.sync()
        let stale = a.store.noteTree.root()
        // The local root lapses in a minute (CheckTx would refuse it within 120 s).
        chain.rootExpiresAt = { [unowned chain] r in r == stale ? chain.now + 60 : 0 }
        let sent = chain.txs.count
        do { _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000); XCTFail("sent on a lapsing anchor") } catch is PrivacyWallet.AnchorTooOld {}
        XCTAssertEqual(sent, chain.txs.count)
        // A newer root exists on the chain: the wallet syncs to it and sends.
        let m = try NoteOut.mintToSelf(b.keys, denom: "uerth")
        chain.shield("uerth", 1, m.pc, m.ciphertext)
        _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000)
        XCTAssertNotEqual(stale, a.store.noteTree.root())
        // With room to spare (> the margin), the root is used as it is.
        chain.rootExpiresAt = { [unowned chain] _ in chain.now + PrivacyWallet.anchorMargin + 60 }
        try await a.sync()
        _ = try await a.send(to: b.address, denom: "uerth", amount: 1_000)
    }

    /// An unshield to any module account is refused before anything is proven.
    func testAnUnshieldToAModuleAccountIsRefused() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try await w.sync()
        let staking = try Bech32.encode(hrp: "earth", data: try Bech32.convertBits([UInt8](PrivateMsgs.moduleAddress("shieldedstaking")), from: 8, to: 5, pad: true))
        await assertThrowsAsync({ try await w.unshield(receiver: staking, denom: "uerth", amount: 1000) }) { String(describing: $0).contains("shieldedstaking") }
        XCTAssertEqual(0, chain.simulated)
        _ = try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000)
    }
}
