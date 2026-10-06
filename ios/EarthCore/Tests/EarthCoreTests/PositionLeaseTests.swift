import Foundation
import XCTest
@testable import EarthCore

/// A position's Groundworks split lease. Ports `PositionsTest.splitLeaseRenewsAndLapses` and
/// `RemindersTest.groundworksLeaseReminders`.
final class PositionLeaseTests: PrivacyTestCase {
    let day: Int64 = 20_000
    var now: Int64 { day * 86_400 + 5 * 3600 }

    /// Reminded as the caretaker vote is (from 30 days before its lease end, for 30 days after),
    /// whatever the identity's state, and never while the lease end is unknown.
    func testGroundworksLeaseReminders() {
        let q = Reminders.Inputs(now: now, identityLive: false, claimOpensAt: nil, claimedToday: true, caretakerExpiresAt: 0, handle: "", handleEntry: nil)
        func due(_ g: Reminders.GroundworksLease...) -> [Reminders.Reminder] { var i = q; i.groundworks = g; return Reminders.due(i) }
        func lease(_ id: UInt64, _ e: Int64, _ held: Bool, _ split: [UInt64: UInt64] = [2: 100]) -> Reminders.GroundworksLease {
            Reminders.GroundworksLease(positionID: id, expiresAt: e, held: held, split: split)
        }
        XCTAssertTrue(due(lease(3, now + Reminders.leadSeconds + 1, true)).isEmpty)
        XCTAssertEqual([.groundworksExpiring(positionID: 3, expiresAt: now + 7 * 86_400, lapsed: false)], due(lease(3, now + 7 * 86_400, true)))
        XCTAssertEqual([.groundworksExpiring(positionID: 3, expiresAt: now + Reminders.leadSeconds, lapsed: false)], due(lease(3, now + Reminders.leadSeconds, true)))
        // Past its end, cleared by the chain or not yet: lapsed.
        XCTAssertEqual([.groundworksExpiring(positionID: 3, expiresAt: now, lapsed: true)], due(lease(3, now, true)))
        XCTAssertEqual([.groundworksExpiring(positionID: 3, expiresAt: now - 86_400, lapsed: true)], due(lease(3, now - 86_400, false)))
        XCTAssertTrue(due(lease(3, now - Reminders.lapsedSeconds - 1, false)).isEmpty)
        // Unknown lease end (a node before leases; a lapse never seen here): no reminder, though the card says lapsed.
        XCTAssertTrue(due(lease(3, 0, true), lease(4, 0, false, [:])).isEmpty)
        XCTAssertTrue(lease(4, 0, false, [:]).lapsed(now))
        XCTAssertFalse(lease(3, 0, true).lapsed(now))
        XCTAssertEqual(2, due(lease(3, now + 86_400, true), lease(5, now - 86_400, false)).count)
        // Hostile times saturate.
        var h = q; h.now = .min; h.groundworks = [lease(1, .max, true)]; _ = Reminders.due(h)
        _ = Reminders.text(.groundworksExpiring(positionID: 1, expiresAt: .max, lapsed: false), now: .min)
        XCTAssertTrue(Reminders.text(.groundworksExpiring(positionID: 3, expiresAt: now + 7 * 86_400, lapsed: false), now: now).contains("expires in 7 days"))
        XCTAssertTrue(Reminders.text(.groundworksExpiring(positionID: 3, expiresAt: now - 1, lapsed: true), now: now).contains("Choose a split again"))
    }

    /// Read from the wallet's own position read, renewed by casting the same split again
    /// (MsgUpdatePosition), and still known after the chain clears a lapsed split, with the split
    /// to cast again. Nothing is sent unless the wallet is asked.
    func testSplitLeaseRenewsAndLapses() async throws {
        let chain = FakeChain()
        chain.groundworksLease = 40 * 86_400
        let a = try wallet(chain)
        for _ in 0 ..< 4 { try funded(chain, a, 2_000_000) }
        try await a.sync()
        _ = try await a.delegate(validator: validator, amount: 1_000_000); try await a.sync()
        _ = try await a.lockPosition(validator: validator, amount: 100_000, splits: [2: 100]); try await a.sync()
        let r0 = try await a.positions()
        let p0 = r0[0].position, c0 = r0[0].counter
        let l0 = await a.groundworksLeases([p0])[0]
        XCTAssertEqual(chain.now + chain.groundworksLease, l0.expiresAt)
        XCTAssertTrue(l0.held && !l0.lapsed(chain.now) && !l0.renewalDue(chain.now))

        // A week before the end: due, and nothing was sent on its own.
        chain.now = l0.expiresAt - 7 * 86_400
        let txs = chain.txs.count
        let l1 = await a.groundworksLeases(try await a.positions().map(\.position))[0]
        XCTAssertTrue(l1.renewalDue(chain.now))
        XCTAssertEqual(txs, chain.txs.count)
        // Renew: the same split, a new lease from now.
        _ = try await a.updatePosition(p0, counter: c0, splits: l1.split); try await a.sync()
        let l2 = await a.groundworksLeases(try await a.positions().map(\.position))[0]
        XCTAssertEqual(chain.now + chain.groundworksLease, l2.expiresAt)
        XCTAssertEqual([2: 100], l2.split)

        // The lapse: the chain clears the split; the wallet still knows when, and what it was.
        chain.now = l2.expiresAt
        chain.lapseSplits()
        let r3 = try await a.positions()
        let p3 = r3[0].position, c3 = r3[0].counter
        XCTAssertTrue(p3.splits.isEmpty)
        let l3 = await a.groundworksLeases([p3])[0]
        XCTAssertTrue(!l3.held && l3.lapsed(chain.now))
        XCTAssertEqual(l2.expiresAt, l3.expiresAt)
        XCTAssertEqual([2: 100], l3.split)
        XCTAssertEqual(.groundworksExpiring(positionID: p3.id, expiresAt: l2.expiresAt, lapsed: true), Reminders.groundworks(l3, now: chain.now))
        // Survives an encode/decode of the store, and a re-cast counts again.
        let decoded = try JSONDecoder().decode(PrivacyState.self, from: try JSONEncoder().encode(a.store.state))
        XCTAssertEqual(l2.expiresAt, decoded.positionLeases[p3.id]?.expiresAt)
        _ = try await a.updatePosition(p3, counter: c3, splits: l3.split); try await a.sync()
        let l4 = await a.groundworksLeases(try await a.positions().map(\.position))[0]
        XCTAssertTrue(l4.held && !l4.lapsed(chain.now))
        // Closed: forgotten.
        _ = try await a.unlockPosition(try await a.positions()[0].position, counter: c3); try await a.sync()
        let left = try await a.positions()
        XCTAssertTrue(left.isEmpty)
        XCTAssertTrue(a.store.state.positionLeases.isEmpty)
    }
}
