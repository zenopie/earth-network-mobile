import Foundation
import XCTest
@testable import EarthCore

/// MsgClaimAnmlDays (chain v1.4.0): a claim takes every unclaimed day up to
/// a week, a run ending today, with one claim proof; the first claim's delay
/// holds; a restored wallet learns the days claimed before it from the
/// chain's refusal and claims after them. Mirrors ClaimDaysTest.kt.
final class ClaimDaysTests: PrivacyTestCase {
    private func registered(_ chain: FakeChain) async throws -> PrivacyWallet {
        let a = try wallet(chain)
        try await a.sync()
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep, "123456789"), signatureAlgorithm: "lean_poa",
                                 dscDer: Data(count: 10))
        try await a.sync()
        XCTAssertEqual(.live, a.identityStatus())
        return a
    }

    func testClaimsEveryUnclaimedDayUpToAWeek() async throws {
        let chain = FakeChain()
        chain.claimDays = true
        let a = try await registered(chain)
        let r = UInt64(chain.now) / 86_400
        // Registered on day r: the first claim is day r + 2, whatever the run.
        let none = try await a.claimOutlook()?.days
        XCTAssertEqual(0, none)
        chain.now += 5 * 86_400
        try await a.sync()
        let four = try await a.claimOutlook()?.days
        XCTAssertEqual(4, four)
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(5_000_000, bal(a, "uanml"))
        let w = try XCTUnwrap(chain.prover.allClaims.last)
        XCTAssertEqual((r + 2 ... r + 5).map { PrivacyHash.claimScope(day: $0) }, w.scopes)
        XCTAssertEqual((r + 1) * 86_400, w.maxActivation)
        XCTAssertTrue(a.claimedToday())
        let zero = try await a.claimOutlook()?.days
        XCTAssertEqual(0, zero)
        await assertThrowsAsync({ try await a.claimAnml() }) { $0 is PrivacyError }

        chain.now += 2 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(7_000_000, bal(a, "uanml"))

        // Ten days away: a week's worth, the rest lapsed.
        chain.now += 10 * 86_400
        try await a.sync()
        let week = try await a.claimOutlook()?.days
        XCTAssertEqual(7, week)
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(14_000_000, bal(a, "uanml"))
        XCTAssertEqual(7, chain.prover.allClaims.last?.scopes.count)
        dump(chain, "claimdays")
    }

    func testRestoredWalletClaimsAfterTheDaysClaimedBeforeIt() async throws {
        let chain = FakeChain()
        chain.claimDays = true
        let a = try await registered(chain)
        chain.now += 6 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        try await a.sync()
        let before = bal(a, "uanml")

        chain.now += 2 * 86_400
        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(.live, restored.identityStatus())
        _ = try await restored.claimAnml()
        try await restored.sync()
        XCTAssertEqual(before + 2_000_000, bal(restored, "uanml"))
        XCTAssertEqual(2, chain.prover.allClaims.last?.scopes.count)
    }

    /// A run the node took that then fails in its block has spent its fee:
    /// reported, never sent again on its own.
    func testAClaimThatFailedInItsBlockIsNotSentAgain() async throws {
        let chain = FakeChain()
        chain.claimDays = true
        let a = try await registered(chain)
        chain.now += 5 * 86_400
        try await a.sync()
        chain.failInBlockNext = 1
        chain.failInBlockCode = 1131
        chain.failInBlockLog = "day \(chain.now / 86_400): a day in this claim has already been claimed"
        await assertThrowsAsync({ try await a.claimAnml() }) { _ in true }
        XCTAssertEqual(1, chain.prover.allClaims.count)
    }

    /// Today claimed by another device: refused, and the claim stops being offered today.
    func testARestoredWalletLearnsTodayIsClaimed() async throws {
        let chain = FakeChain()
        chain.claimDays = true
        let a = try await registered(chain)
        chain.now += 5 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(0, restored.claimOpensAt())
        await assertThrowsAsync({ try await restored.claimAnml() }) { $0 is PrivacyError }
        XCTAssertTrue(restored.claimedToday())
        XCTAssertGreaterThan(restored.claimOpensAt() ?? 0, 0)
        let days = try await restored.claimOutlook()?.days
        XCTAssertEqual(0, days)
    }

    func testBeforeTheUpgradeTodayAlone() async throws {
        let chain = FakeChain()
        let a = try await registered(chain)
        chain.now += 5 * 86_400
        try await a.sync()
        let one = try await a.claimOutlook()?.days
        XCTAssertEqual(1, one)
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(2_000_000, bal(a, "uanml"))
        XCTAssertTrue(chain.prover.allClaims.isEmpty)
    }

    func testTheFloorBoundsTheRun() async throws {
        let chain = FakeChain()
        chain.claimDays = true
        let a = try await registered(chain)
        chain.now += 6 * 86_400
        chain.claimFloor = UInt64(chain.now) / 86_400 - 1
        try await a.sync()
        let two = try await a.claimOutlook()?.days
        XCTAssertEqual(2, two)
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(3_000_000, bal(a, "uanml"))
    }

    func testClaimedDayIsReadFromTheRefusal() {
        struct E: LocalizedError { var errorDescription: String? { "simulate: day 20091: a day in this claim has already been claimed" } }
        XCTAssertEqual(20091, PrivacyWallet.claimedDay(in: E()))
        XCTAssertNil(PrivacyWallet.claimedDay(in: PrivacyError("already claimed today")))
    }
}
