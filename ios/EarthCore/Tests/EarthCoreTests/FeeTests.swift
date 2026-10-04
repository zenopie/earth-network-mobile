import BigInt
import Foundation
import XCTest
@testable import EarthCore

/// Fees and gas: a private fee capped and reconfirmed above what the sheet
/// showed, quotes simulated without our nullifiers, every private tx
/// declaring within the chain's 5x of its gas, the vote's estimate.
final class FeeTests: PrivacyTestCase {
    func testTheFeeIsCappedAndReconfirmedAboveTheSheet() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w, 50_000_000)
        try await w.sync()
        chain.price = 10
        await assertThrowsAsync({ try await w.unshield(receiver: self.receiver, denom: "uerth", amount: 1000) }) {
            guard let e = $0 as? PrivateTxEngine.FeeAboveCap else { return false }
            return e.cap <= PrivateTxEngine.maxPrivateFee
        }
        XCTAssertTrue(chain.prover.actions.isEmpty)
        chain.price = Decimal(string: "0.001")!
        let before = chain.height
        var shown: UInt64 = 0
        do {
            _ = try await PrivacyWallet.$shownFee.withValue(1) { try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000) }
            XCTFail("expected FeeAboveQuote")
        } catch let e as PrivateTxEngine.FeeAboveQuote {
            shown = e.fee
        }
        XCTAssertEqual(before, chain.height)
        _ = try await PrivacyWallet.$shownFee.withValue(shown) { try await w.unshield(receiver: receiver, denom: "uerth", amount: 1000) }
        XCTAssertEqual(before + 1, chain.height)
    }

    func testAQuoteNeverShowsTheNodeOurNullifiers() async throws {
        let chain = FakeChain()
        let w = try wallet(chain)
        try funded(chain, w)
        try funded(chain, w)
        try await w.sync()
        let q = try await w.quoteSend(to: w.address, denom: "uerth", amount: 1_500_000)
        XCTAssertGreaterThan(q.fee, 0)
        let ours = Set(w.notes.map(\.nf))
        XCTAssertFalse(chain.simulatedNullifiers.isEmpty)
        XCTAssertTrue(chain.simulatedNullifiers.allSatisfy { !ours.contains($0) })
        XCTAssertTrue(chain.prover.actions.isEmpty)
    }

    func testEveryPrivateTxDeclaresWithinFiveTimesItsGas() async throws {
        let chain = FakeChain()
        let a = try await delegatedWallet(chain)
        _ = try await a.delegate(validator: validator, amount: 500_000); try await a.sync()
        // A second note (another device's) merged by a restake.
        chain.plantStake(a.keys, PrivacyWallet.derthDenom(validator), 50_000); try await a.sync()
        _ = try await a.restake(validator: validator); try await a.sync()
        chain.openProposal(9); try await a.sync()
        _ = try await a.stakeVote(proposalID: 9, validator: validator, options: yes); try await a.sync()
        _ = try await a.undelegate(validator: validator, amount: 100_000); try await a.sync()
        XCTAssertFalse(chain.gasRatios.isEmpty)
        // simulate + 10% (at least the fixed headroom): well inside the chain's 5x.
        XCTAssertTrue(chain.gasRatios.allSatisfy { (1.0 ... 1.2).contains($0) }, "\(chain.gasRatios)")
        dump(chain, "gas")
    }

    func testTheVoteGasEstimateCountsItsNotes() throws {
        func spec(_ used: Int) throws -> VoteWitnessSpec {
            try VoteWitnessSpec(vnfs: (0 ..< 2).map { $0 < used ? Fr(UInt64($0 + 1)) : .zero }) { _ in throw PrivacyError("unused") }
        }
        let msg = MsgStakeVote(bundle: ShieldedBundle(actions: [], balances: [], bindingSig: Data()), proposalID: 1, validator: validator, options: [], weight: 1)
        let g1 = PrivateTxEngine.estimateGas(msg, Assembled(bundles: [], vote: try spec(1)) { _, _, _ in msg }, txBytes: 0)
        let g2 = PrivateTxEngine.estimateGas(msg, Assembled(bundles: [], vote: try spec(2)) { _, _, _ in msg }, txBytes: 0)
        XCTAssertEqual(PrivateTxEngine.noteGas, g2 - g1)
        XCTAssertEqual(PrivateTxEngine.baseGas + PrivateTxEngine.bundleGas + 250_000 + 2_000_000 + 2 * PrivateTxEngine.noteGas, g1)
    }
}
