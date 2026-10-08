import XCTest
@testable import EarthCore

/// The private activity list, built from the sealed store alone: sent txs as
/// recorded (their change and minted notes folded in), received notes
/// labelled, the pending to confirmed transition from sync, failures, and a
/// restored wallet's spends inferred. Ports PrivateActivityTest.kt.
final class PrivateActivityTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    func wallet(_ chain: FakeChain, _ words: String, store: PrivacyStore = .memory()) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: store, indexer: chain, chain: chain,
                      reads: FakeReads(chain: chain), prover: chain.prover, chainID: chain.chainID, roots: chain,
                      now: { [unowned chain] in chain.now })
    }

    /// Alice: gas grant, registration.
    func registered(_ chain: FakeChain) async throws -> PrivacyWallet {
        let a = try wallet(chain, alice)
        try await a.sync()
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        let signals = ["261001", prep.binding.bigUInt.description, "123456789", Fr(UInt64(77)).bigUInt.description, prep.idc.bigUInt.description]
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals, signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        return a
    }

    func testSentTxsFoldTheirOwnNotesAndReceivedNotesAreLabelled() async throws {
        let chain = FakeChain()
        let a = try await registered(chain)
        var rows = a.activity()
        // The gas grant: a note the wallet expected, labelled as one.
        let gas = try XCTUnwrap(rows.first { $0.kind == .gasGrant })
        XCTAssertEqual([ActivityCoin("uerth", 100_000)], gas.coins)
        XCTAssertNil(gas.hash)
        // The registration: its reward notes folded in as what came in, its change never a row.
        let reg = try XCTUnwrap(rows.first { $0.kind == .register })
        XCTAssertEqual(.confirmed, reg.status)
        XCTAssertNotNil(reg.hash)
        XCTAssertGreaterThan(reg.fee ?? 0, 0)
        // Its fee came out of the gas grant's note.
        XCTAssertTrue(reg.feeFromGrant)
        XCTAssertEqual("Earth (gas grant)", ActivityEntry(private: reg).feePayer)
        XCTAssertTrue(reg.coins.contains(ActivityCoin("uanml", 1_000_000)))
        XCTAssertTrue(reg.coins.contains { $0.denom == "uerth" && $0.amount > 0 })
        XCTAssertEqual(2, rows.count)

        chain.now += 2 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        try await a.sync()
        let claim = try XCTUnwrap(a.activity().first { $0.kind == .claimAnml })
        XCTAssertEqual(.confirmed, claim.status)
        XCTAssertTrue(claim.coins.contains(ActivityCoin("uanml", 1_000_000)))

        // A private send: out exactly the amount (the fee apart), its change folded.
        let b = try wallet(chain, bob)
        try await b.sync()
        _ = try await a.send(to: b.address, denom: "uanml", amount: 700_000, counterparty: "@bob")
        try await a.sync(); try await b.sync()
        rows = a.activity()
        let send = try XCTUnwrap(rows.first { $0.kind == .send })
        XCTAssertEqual([ActivityCoin("uanml", -700_000)], send.coins)
        XCTAssertEqual("@bob", send.counterparty)
        XCTAssertFalse(rows.contains { [.received, .fromEarth, .inferred].contains($0.kind) })
        XCTAssertEqual(.send, rows.first?.kind)

        // Bob: a v1 note from someone else.
        let got = try XCTUnwrap(b.activity().first)
        XCTAssertEqual(1, b.activity().count)
        XCTAssertEqual(.received, got.kind)
        XCTAssertEqual([ActivityCoin("uanml", 700_000)], got.coins)
        XCTAssertEqual(.confirmed, got.status)
        XCTAssertNotNil(got.height)

        // A note the chain minted that the wallet did not expect: from Earth.
        let o = try NoteOut.mintToSelf(b.keys, denom: "uerth")
        chain.shield("uerth", 5_000, o.pc, o.ciphertext)
        try await b.sync()
        XCTAssertEqual(.fromEarth, b.activity().first { $0.coins.first?.amount == 5_000 }?.kind)
        // A shield of the wallet's own: its public row shows it, never a second one.
        let own = try b.shieldOutput(denom: "uerth", amount: 7_000)
        chain.shield("uerth", 7_000, own.pc, own.ciphertext)
        try await b.sync()
        XCTAssertFalse(b.activity().contains { $0.coins.contains { $0.amount == 7_000 } })
    }

    func testPendingUntilSyncSeesItThenConfirmed() async throws {
        let chain = FakeChain()
        let a = try await registered(chain)
        let b = try wallet(chain, bob)
        // The node takes it; the wait never sees its block.
        chain.unconfirmedNext = 1
        _ = try? await a.send(to: b.address, denom: "uanml", amount: 100_000)
        let pending = try XCTUnwrap(a.activity().first { $0.kind == .send })
        XCTAssertEqual(.pending, pending.status)
        XCTAssertNil(pending.height)
        try await a.sync()
        let done = try XCTUnwrap(a.activity().first { $0.kind == .send })
        XCTAssertEqual(.confirmed, done.status)
        XCTAssertEqual(chain.height - 1, done.height)
    }

    func testFailedAndRefusedTxs() async throws {
        let chain = FakeChain()
        let a = try await registered(chain)
        let b = try wallet(chain, bob)
        // Failed in its block, as the broadcast's own wait saw.
        chain.failInBlockNext = 1
        _ = try? await a.send(to: b.address, denom: "uanml", amount: 100_000)
        let failed = try XCTUnwrap(a.activity().first { $0.kind == .send })
        XCTAssertEqual(.failed, failed.status)
        XCTAssertTrue(failed.failure?.hasPrefix("tx failed") == true)
        // Refused by CheckTx: in no mempool, so nothing happened and no row.
        chain.rejectNext = 1
        _ = try? await a.send(to: b.address, denom: "uanml", amount: 200_000)
        XCTAssertEqual(1, a.activity().filter { $0.kind == .send }.count)
    }

    func testRestoredWalletInfersSpendsAndRebuildsReceived() async throws {
        let chain = FakeChain()
        let a = try await registered(chain)
        let b = try wallet(chain, bob)
        try await b.sync()
        _ = try await a.send(to: b.address, denom: "uanml", amount: 300_000)
        try await a.sync()

        let r = try wallet(chain, alice)
        try await r.sync()
        let rows = r.activity()
        // No sent record survives a restore: none has a hash or a fee.
        XCTAssertTrue(rows.allSatisfy { $0.hash == nil && $0.fee == nil })
        // The registration, by its own record note's height, net of its notes.
        let reg = try XCTUnwrap(rows.first { $0.kind == .register })
        XCTAssertEqual(1, rows.filter { $0.kind == .register }.count)
        XCTAssertTrue(reg.coins.contains(ActivityCoin("uanml", 1_000_000)))
        // The send: a private transaction, its spent amount net of its change; nothing more said.
        let send = try XCTUnwrap(rows.first { $0.kind == .inferred })
        XCTAssertEqual(1, rows.filter { $0.kind == .inferred }.count)
        XCTAssertTrue(send.coins.contains(ActivityCoin("uanml", -300_000)))
        XCTAssertEqual("", send.counterparty)
        // The gas grant, rebuilt from sync as a note the chain minted.
        XCTAssertEqual([ActivityCoin("uerth", 100_000)], rows.first { $0.kind == .fromEarth }?.coins)
        XCTAssertFalse(rows.contains { $0.kind == .received })
    }

    func testActivityIsSealedStateAndGoesWithTheStore() async throws {
        let chain = FakeChain()
        let a = try await registered(chain)
        let json = try JSONEncoder().encode(a.store.state)
        let back = try JSONDecoder().decode(PrivacyState.self, from: json)
        XCTAssertEqual(a.store.state.activity, back.activity)
        XCTAssertEqual(PrivateActivity.rows(a.store.state), PrivateActivity.rows(back))
        // A reset on the same chain keeps what the wallet sent; the resync folds the notes in again.
        try a.store.reset(chainID: chain.chainID)
        try await a.sync()
        XCTAssertEqual(1, a.activity().filter { $0.kind == .register && $0.hash != nil }.count)
        // A relaunch drops it with the rest of the old chain's bookkeeping.
        try a.store.switchGenesis("fedcba9876543210")
        XCTAssertTrue(a.store.state.activity.sent.isEmpty)
    }

    func testClockEstimatesTimesFromHeights() {
        let clock = [ActivityLog.Tick(height: 100, time: 1_000), ActivityLog.Tick(height: 200, time: 1_600)]
        XCTAssertEqual(1_300, PrivateActivity.timeAt(clock, 150))
        XCTAssertEqual(1_000 - 60, PrivateActivity.timeAt(clock, 90))
        XCTAssertEqual(1_600 + 60, PrivateActivity.timeAt(clock, 210))
        XCTAssertEqual(1_000 - 10 * PrivateActivity.defaultBlockSeconds, PrivateActivity.timeAt([clock[0]], 90))
        XCTAssertNil(PrivateActivity.timeAt([], 5))
        var log = ActivityLog()
        for h in UInt64(1) ... 100 { log.tick(height: h * 10, time: Int64(h) * 60) }
        XCTAssertEqual(ActivityLog.maxClock, log.clock.count)
        XCTAssertEqual(10, log.clock.first?.height)
        XCTAssertEqual(1_000, log.clock.last?.height)
    }

    func testCoinsNetSpendsAgainstChangeAndFee() {
        let (outs, ins) = PrivateActivity.coins(
            poolSpent: [ActivityCoin("uerth", 1_000), ActivityCoin("uanml", 500)],
            poolBack: [ActivityCoin("uerth", 890), ActivityCoin("uanml", 0)],
            stakeSpent: [],
            stakeBack: [ActivityCoin("derth/v", 70)],
            fee: 10)
        XCTAssertEqual([ActivityCoin("uanml", 500), ActivityCoin("uerth", 100)], outs)
        XCTAssertEqual([ActivityCoin("derth/v", 70)], ins)
    }
}
