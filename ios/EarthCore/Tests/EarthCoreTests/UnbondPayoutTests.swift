import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Undelegations pay out by themselves: the msg names its payout note, the
/// wallet keeps its own record (due time from chain-wide epoch timing, no
/// per-id query), a split payout is found whole, a refused or failed one is
/// forgotten, and the record survives a reset.
final class UnbondPayoutTests: PrivacyTestCase {
    func testAnUndelegationNamesItsPayoutAndMintsNoStakeNote() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain, due: { Int64($0) * 1000 })
        let stakeRows = chain.stakeRows.count
        _ = try await a.undelegate(validator: validator, amount: 400_000)
        let m = try XCTUnwrap(chain.lastMsg as? MsgShieldedUndelegate)
        XCTAssertEqual(32, m.pc.count)
        XCTAssertEqual(NoteCipher.blindCiphertextBytes, m.ciphertext.count)
        // Lane A's output (the change) with its 201-byte ciphertext; no credit lane.
        XCTAssertEqual(NoteCipher.stakeCiphertextBytes, m.stake.ciphertext.count)
        XCTAssertTrue(m.stake.creditCommitment.allSatisfy { $0 == 0 } && m.stake.creditCiphertext.isEmpty)
        // The change is the proof's own output; nothing minted into the stake tree.
        XCTAssertEqual(stakeRows + 1, chain.stakeRows.count)
        let u = try XCTUnwrap(a.pendingUnbonds.first)
        XCTAssertEqual(1, a.pendingUnbonds.count)
        XCTAssertEqual(try Fr(bytes: m.pc), u.pc)
        XCTAssertEqual(400_000 * 10 / 9, u.value)
        XCTAssertEqual(chain.epoch, u.epoch)
        XCTAssertEqual(1, u.payoutID)
        XCTAssertEqual(Int64(chain.epoch) * 1000, u.dueBy)
        XCTAssertTrue(u.confirmed)
        dump(chain, "undelegate")
    }

    /// A payout past one note's worth comes as several notes, one ciphertext at several positions: every one is found.
    func testASplitPayoutIsFoundWhole() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        // The whole note: the proof's output is a zero note.
        let all = a.stakeBalances()[PrivacyWallet.derthDenom(validator)]!
        _ = try await a.undelegate(validator: validator, amount: all)
        let pc = try Fr(bytes: (chain.lastMsg as! MsgShieldedUndelegate).pc)
        try await a.sync()
        let before = bal(a)
        chain.payUnbonds { p in [p.value / 3, p.value / 3, p.value - 2 * (p.value / 3)] }
        let txs = chain.txs.count
        try await a.sync()
        XCTAssertEqual(txs, chain.txs.count)
        XCTAssertEqual(all * 10 / 9, bal(a) - before)
        XCTAssertEqual(3, a.notes.filter { $0.note.pc(ownerPK: a.keys.ownerPK) == pc }.count)
        XCTAssertFalse(a.stakeNotes.contains(where: \.spendable))
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
        // A restored wallet finds the payout by trial decryption alone.
        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(a.balances(), restored.balances())
        dump(chain, "splitPayout")
    }

    func testAFailedOrRefusedUndelegationIsForgotten() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        chain.rejectNext = 1
        await assertThrowsAsync({ try await a.undelegate(validator: self.validator, amount: 100_000) })
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
        chain.failInBlockNext = 1
        await assertThrowsAsync({ try await a.undelegate(validator: self.validator, amount: 100_000) })
        XCTAssertEqual(1, a.pendingUnbonds.count)
        try await a.sync()
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
        XCTAssertTrue(chain.unbondPayouts.isEmpty)
    }

    func testPendingUnbondsSurviveAReset() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        _ = try await a.undelegate(validator: validator, amount: 100_000)
        let kept = a.store.state.pendingUnbonds
        try a.store.reset(chainID: chain.chainID)
        XCTAssertEqual(kept, a.store.state.pendingUnbonds)
    }

    func testPayoutTimeComesFromEpochTimingAlone() {
        let d: Int64 = 86_400, unbonding = 21 * d, t: Int64 = 1_800_000_000
        let m = PrivacyWallet.payoutMarginSeconds
        // Booked in the epoch in progress: it ends with it, then unbonds.
        XCTAssertEqual(t + d + unbonding + m, PrivacyWallet.unbondDueBy(epoch: 10, current: 10, currentStart: t, currentEnd: t + d, epochSeconds: d, unbondingSeconds: unbonding))
        // A late epoch end (the end time passed): at least one epoch from its start.
        XCTAssertEqual(t + d + unbonding + m, PrivacyWallet.unbondDueBy(epoch: 10, current: 10, currentStart: t, currentEnd: t, epochSeconds: d, unbondingSeconds: unbonding))
        // An ended epoch: 9 ended at t, 7 at least two epochs earlier.
        XCTAssertEqual(t + unbonding + m, PrivacyWallet.unbondDueBy(epoch: 9, current: 10, currentStart: t, currentEnd: t + d, epochSeconds: d, unbondingSeconds: unbonding))
        XCTAssertEqual(t - 2 * d + unbonding + m, PrivacyWallet.unbondDueBy(epoch: 7, current: 10, currentStart: t, currentEnd: t + d, epochSeconds: d, unbondingSeconds: unbonding))
        // Chain-supplied numbers never wrap or trap.
        XCTAssertNil(PrivacyWallet.unbondDueBy(epoch: 0, current: UInt64(Int64.max), currentStart: 0, currentEnd: 0, epochSeconds: Int64.max, unbondingSeconds: 0))
        XCTAssertNil(PrivacyWallet.unbondDueBy(epoch: 1, current: 1, currentStart: Int64.max, currentEnd: Int64.max, epochSeconds: 1, unbondingSeconds: Int64.max))
        XCTAssertNil(PrivacyWallet.unbondDueBy(epoch: 1, current: 3, currentStart: 0, currentEnd: 1, epochSeconds: 0, unbondingSeconds: 1))
        XCTAssertNil(PrivacyWallet.unbondDueBy(epoch: UInt64.max, current: 3, currentStart: 0, currentEnd: 1, epochSeconds: 1, unbondingSeconds: 1))
    }
}
