import BigInt
import XCTest
@testable import EarthCore

/// A wallet driven end to end against `FakeChain` (ports WalletFlowTest.kt):
/// gas grant, registration, claim, assembly vote, private send and receive,
/// unshield, delegation and a stake vote against a snapshot root, the dex,
/// positions and personhood paths, the single-ERTH-note layout, then wallets
/// restored from their mnemonics finding everything again.
///
/// With PRIVACY_TOML_OUT=<dir>, every witness proved is written as a nargo
/// Prover.toml, for `nargo execute` against the real circuits.
final class WalletFlowTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    var snapshot: UInt64?

    func reads(_ chain: FakeChain, _ size: (() -> UInt64)? = nil) -> FakeReads {
        FakeReads(chain: chain, snapshotSize: size ?? { [unowned self] in self.snapshot ?? chain.noteTree.size })
    }

    func wallet(_ chain: FakeChain, _ words: String, reads r: FakeReads? = nil) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: .memory(), indexer: chain, chain: chain,
                      reads: r ?? reads(chain), prover: chain.prover, chainID: chain.chainID, now: { [unowned chain] in chain.now })
    }

    func signals(_ prep: PrivacyWallet.RegistrationPrep) -> [String] {
        ["261001", prep.binding.bigUInt.description, "123456789", Fr(UInt64(77)).bigUInt.description]
    }

    func bal(_ w: PrivacyWallet, _ d: String) -> UInt64 { w.balances()[d] ?? 0 }

    func yes() -> [WeightedVoteOption] { [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")] }

    func testEndToEnd() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await a.sync()
        XCTAssertEqual(.none, a.identityStatus())

        // Registration: prepare, the backend shields gas to pc_gas, register.
        let prep = try await a.prepareRegistration(affiliate: nil)
        chain.shield("uerth", 100_000, prep.gas.pc)
        try await a.sync()
        XCTAssertEqual(100_000, a.balances()["uerth"])
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        XCTAssertEqual(.live, a.identityStatus())
        XCTAssertEqual(1_000_000, a.balances()["uanml"])
        // The gas note paid the fee; the reward is a self-mint found by its public amount.
        XCTAssertGreaterThan(bal(a, "uerth"), 5_000_000)
        XCTAssertEqual(PrivacyHash.countryField("DE"), a.snapshot.identity?.country)

        // Too soon to claim: activated today.
        XCTAssertEqual(chain.now / 86_400 * 86_400 + 2 * 86_400, a.claimOpensAt())
        chain.now += 2 * 86_400
        try await a.sync()
        XCTAssertEqual(0, a.claimOpensAt())
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(2_000_000, a.balances()["uanml"])
        XCTAssertTrue(a.claimedToday())

        // An assembly vote.
        chain.now += 7200
        _ = try await a.voteProposal(proposalID: 5, yes: true)
        XCTAssertEqual(5, chain.votes.last?.0)
        XCTAssertEqual(1, chain.votes.last?.1)

        // Private send to bob, who finds it.
        let b = try wallet(chain, bob)
        try await a.sync()
        _ = try await a.send(to: b.address, denom: "uanml", amount: 700_000, memo: Data("hi".utf8))
        try await a.sync(); try await b.sync()
        XCTAssertEqual(1_300_000, a.balances()["uanml"])
        XCTAssertEqual(700_000, b.balances()["uanml"])
        XCTAssertEqual("hi", String(decoding: b.notes.first { $0.note.denom == "uanml" }!.note.memo, as: UTF8.self))

        // Unshield ERTH to an address.
        let before = bal(a, "uerth")
        _ = try await a.unshield(receiver: receiver, denom: "uerth", amount: 1_000_000)
        try await a.sync()
        XCTAssertEqual(1_000_000, chain.unshielded[receiver])
        XCTAssertLessThan(bal(a, "uerth"), before - 1_000_000)

        // Stake, then vote with the derth against a snapshot taken right after.
        _ = try await a.delegate(validator: validator, amount: 2_000_000)
        try await a.sync()
        let derth = PrivacyWallet.derthDenom(validator)
        XCTAssertEqual(1_800_000, a.balances()[derth])
        let snap = chain.noteTree.size
        let fresh = try wallet(chain, alice, reads: reads(chain) { snap })
        try await fresh.sync()
        // A later note moves the tree past the snapshot.
        chain.shield("uerth", 1, Fr(UInt64(5)))
        try await fresh.sync()
        _ = try await fresh.stakeVote(proposalID: 9, note: fresh.notes.first { $0.note.denom == derth && $0.unspent }!, options: yes())
        try await fresh.sync()
        XCTAssertEqual(1_800_000, fresh.balances()[derth])

        // A wallet restored from the mnemonic alone sees the same balances,
        // the self-mints (gas, reward, derth) included.
        let restored = try wallet(chain, alice)
        try await restored.sync()
        XCTAssertEqual(fresh.balances(), restored.balances())
        XCTAssertGreaterThan(chain.simulated, 0)
        dump(chain, "endToEnd")
    }

    /// A registered wallet holding ANML from its registration and a claim, and its reward ERTH.
    func registered(_ chain: FakeChain, _ words: String) async throws -> PrivacyWallet {
        let a = try wallet(chain, words)
        try await a.sync()
        let prep = try await a.prepareRegistration(affiliate: nil)
        chain.shield("uerth", 100_000, prep.gas.pc)
        try await a.sync()
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        chain.now += 2 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        try await a.sync()
        return a
    }

    func testDexPositionsAndPersonhood() async throws {
        let chain = FakeChain()
        let a = try await registered(chain, alice)
        let b = try wallet(chain, bob)
        try await b.sync()
        XCTAssertEqual(2_000_000, bal(a, "uanml"))
        let pool = { ["uanml": SwapMath.Reserves(erth: chain.poolErth, token: chain.poolAnml)] }

        // ANML -> ERTH: the fee comes out of the output.
        let erth0 = bal(a, "uerth")
        let q = SwapMath.route(pools: pool(), hub: "uerth", denomIn: "uanml", amountIn: 600_000, denomOut: "uerth", feePercent: chain.swapFee)!
        _ = try await a.noteSwap(denomIn: "uanml", amountIn: 600_000, denomOut: "uerth", minOut: UInt64(SwapMath.withSlippage(q.amountOut, bps: 100)))
        try await a.sync()
        XCTAssertEqual(1_400_000, bal(a, "uanml"))
        let fee = Int64(q.amountOut) - Int64(bal(a, "uerth") - erth0)
        XCTAssertTrue((1000 ... 10_000).contains(fee), "fee from output: \(fee)")

        // ERTH -> ANML by bob from one ERTH note: it pays the swap and its fee.
        _ = try await a.send(to: b.address, denom: "uerth", amount: 2_000_000)
        try await a.sync(); try await b.sync()
        XCTAssertEqual(1, b.notes.filter { $0.note.denom == "uerth" && $0.unspent }.count)
        let bq = SwapMath.route(pools: pool(), hub: "uerth", denomIn: "uerth", amountIn: 1_000_000, denomOut: "uanml", feePercent: chain.swapFee)!
        _ = try await b.noteSwap(denomIn: "uerth", amountIn: 1_000_000, denomOut: "uanml", minOut: UInt64(SwapMath.withSlippage(bq.amountOut, bps: 50)))
        try await b.sync()
        XCTAssertEqual(UInt64(bq.amountOut), bal(b, "uanml"))
        XCTAssertTrue((900_000 ..< 1_000_000).contains(bal(b, "uerth")))

        // A swap paid to someone else: bob opens its value-blind (v2) ciphertext.
        let bAnml = bal(b, "uanml"), bErth = bal(b, "uerth")
        let gq = SwapMath.route(pools: pool(), hub: "uerth", denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", feePercent: chain.swapFee)!
        _ = try await a.noteSwap(denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", minOut: UInt64(SwapMath.withSlippage(gq.amountOut, bps: 100)), to: b.address)
        try await a.sync(); try await b.sync()
        XCTAssertEqual(bAnml, bal(b, "uanml"))
        let paid = chain.notes.last!
        XCTAssertEqual(177, paid.ciphertext.count)
        XCTAssertEqual("\(bal(b, "uerth") - bErth)uerth", paid.amount)
        XCTAssertGreaterThan(bal(b, "uerth"), bErth)
        XCTAssertEqual(1_300_000, bal(a, "uanml"))

        // A bound the pool cannot meet fails before anything is spent.
        let before = a.balances()
        do { _ = try await a.noteSwap(denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", minOut: 10_000_000); XCTFail("slippage passed") } catch {}
        try await a.sync()
        XCTAssertEqual(before, a.balances())

        // Pool-1 liquidity from notes: what the ratio does not take comes back.
        let erthBefore = bal(a, "uerth")
        _ = try await a.addLiquidityShielded(poolID: 1, token: "uanml", tokenAmount: 400_000, erthAmount: 2_000_000, provider: receiver, minShares: "1")
        try await a.sync()
        XCTAssertGreaterThan(chain.lpShares[receiver] ?? 0, 0)
        let anmlAfterLp = bal(a, "uanml")
        XCTAssertTrue((900_000 ... 900_010).contains(anmlAfterLp))
        let spent = erthBefore - bal(a, "uerth")
        XCTAssertTrue((800_000 ... 810_000).contains(spent), "the unused ERTH is refunded: spent \(spent)")

        // Groundworks: stake, lock a position, re-split it, vote with it, unlock.
        let derth = PrivacyWallet.derthDenom(validator)
        _ = try await a.delegate(validator: validator, amount: 2_000_000)
        try await a.sync()
        XCTAssertEqual(1_800_000, bal(a, derth))
        _ = try await a.lockPosition(validator: validator, amount: 1_000_000, splits: [2: 60, 5: 40])
        try await a.sync()
        XCTAssertEqual(800_000, bal(a, derth))
        let mine = try await a.positions()
        XCTAssertEqual(1, mine.count)
        let (pos, key) = (mine[0].position, mine[0].keyIndex)
        XCTAssertEqual([2: 60, 5: 40], pos.splits)
        _ = try await a.updatePosition(pos, keyIndex: key, splits: [2: 100])
        XCTAssertEqual([2: 100], chain.positions[pos.id]?.splits)
        _ = try await a.positionVote(try await a.positions()[0].position, keyIndex: key, proposalID: 9, options: yes())
        XCTAssertEqual(9, chain.positionVotes.first?.1)
        _ = try await a.unlockPosition(try await a.positions()[0].position, keyIndex: key)
        try await a.sync()
        let left = try await a.positions()
        XCTAssertTrue(left.isEmpty)
        XCTAssertEqual(1_800_000, bal(a, derth))

        // Stake votes: every derth note from before the snapshot, each once.
        try await a.sync()
        snapshot = chain.noteTree.size
        let weight = try await a.stakeVoteWeight(proposalID: 11, positions: [])
        XCTAssertEqual(2, weight.notes)
        XCTAssertEqual(1_800_000, weight.uerth, "two derth notes at the fake's rate of 1")
        let voted = try await a.stakeVoteAll(proposalID: 11, options: yes())
        snapshot = nil
        XCTAssertEqual(2, voted.count)
        try await a.sync()
        XCTAssertEqual(1_800_000, bal(a, derth))

        // Referrer binding (lapses after R; refreshed past R/2).
        chain.now += 31 * 86_400
        try await a.sync()
        _ = try await a.bindReferrer(address: receiver)
        XCTAssertEqual([receiver], Array(chain.referrers.values))
        let due0 = try await a.referrerDue()
        XCTAssertFalse(due0)
        chain.now += 16 * 86_400
        let due1 = try await a.referrerDue()
        XCTAssertTrue(due1)

        // Caretaker split.
        try await a.sync()
        _ = try await a.setCaretaker(split: [1: 70, 2: 30])
        XCTAssertEqual([1: 70, 2: 30], chain.caretakerVotes.values.first)

        // Removal ballot: open one, vote in it.
        try await a.sync()
        _ = try await a.proposeRemoval(optionID: 3)
        _ = try await a.voteRemoval(optionID: 3, yes: true)
        XCTAssertEqual(3, chain.removalVotes.first?.0)

        // Small notes merge two at a time.
        try await a.sync()
        for i in 0 ..< 3 as Range<UInt64> {
            let o = try a.shieldOutput(denom: "uanml", amount: 1_000 + i)
            chain.shield("uanml", o.value, o.pc, o.ciphertext)
        }
        try await a.sync()
        let n0 = a.mergeable()["uanml"]!
        _ = try await a.merge(denom: "uanml")
        try await a.sync()
        XCTAssertEqual(n0 - 1, a.mergeable()["uanml"])
        XCTAssertEqual(anmlAfterLp + 3_003, bal(a, "uanml"))

        // Unstake; the automation's maturity rule (by timing alone) releases the claim.
        _ = try await a.undelegate(validator: validator, amount: 500_000)
        try await a.sync()
        let unbond = PrivacyWallet.unbondDenom(validator, epoch: 4)
        XCTAssertEqual(500_000, bal(a, unbond))
        let start = chain.now
        XCTAssertTrue(PrivacyAutomation.matured(a.notes, now: start, current: 5, currentStart: start, epochSeconds: 86_400,
                                                unbondingSeconds: 21 * 86_400, retryAt: [:]).isEmpty)
        let ready = PrivacyAutomation.matured(a.notes, now: start + 21 * 86_400 + 3600, current: 5, currentStart: start, epochSeconds: 86_400,
                                              unbondingSeconds: 21 * 86_400, retryAt: [:])
        let erthPre = bal(a, "uerth")
        _ = try await a.claimUnbonding(note: ready[0])
        try await a.sync()
        XCTAssertEqual(0, bal(a, unbond))
        XCTAssertTrue((490_000 ..< 500_000).contains(bal(a, "uerth") - erthPre))

        // Both wallets, restored from their mnemonics, find everything again.
        for (w, words) in [(a, alice), (b, bob)] {
            let restored = try wallet(chain, words)
            try await restored.sync()
            XCTAssertEqual(w.balances(), restored.balances())
        }
        dump(chain, "dex")
    }

    /// A wallet whose shielded ERTH is one note sends, unshields, stakes and
    /// merges ERTH with the fee paid from the same notes (the transfer
    /// circuit's combined ERTH balance), while a non-ERTH spend still needs an
    /// ERTH note for its fee.
    func testSingleErthNote() async throws {
        let chain = FakeChain()
        let c = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try await c.sync(); try await b.sync()
        let o = try c.shieldOutput(denom: "uerth", amount: 3_000_000)
        chain.shield("uerth", o.value, o.pc, o.ciphertext)
        try await c.sync()
        let erthNotes = { c.notes.filter { $0.note.denom == "uerth" && $0.unspent && $0.note.value > 0 } }
        XCTAssertEqual(1, erthNotes().count)

        _ = try await c.send(to: b.address, denom: "uerth", amount: 1_000_000)
        try await c.sync(); try await b.sync()
        XCTAssertEqual(1_000_000, bal(b, "uerth"))
        let afterSend = bal(c, "uerth")
        XCTAssertGreaterThan(2_000_000, afterSend)
        XCTAssertEqual(1, erthNotes().count)

        _ = try await c.unshield(receiver: receiver, denom: "uerth", amount: 500_000)
        try await c.sync()
        XCTAssertEqual(500_000, chain.unshielded[receiver])
        XCTAssertEqual(1, erthNotes().count)
        XCTAssertLessThan(bal(c, "uerth"), afterSend - 500_000)

        _ = try await c.delegate(validator: validator, amount: 400_000)
        try await c.sync()
        XCTAssertEqual(1, erthNotes().count)
        XCTAssertGreaterThan(bal(c, PrivacyWallet.derthDenom(validator)), 0)

        // ERTH merges three notes into one, the fee paid from them.
        for i in 0 ..< 2 as Range<UInt64> {
            let n = try c.shieldOutput(denom: "uerth", amount: 10_000 + i)
            chain.shield("uerth", n.value, n.pc, n.ciphertext)
        }
        try await c.sync()
        XCTAssertEqual(3, erthNotes().count)
        XCTAssertEqual(3, c.mergeable()["uerth"])
        let pre = bal(c, "uerth")
        _ = try await c.merge(denom: "uerth")
        try await c.sync()
        XCTAssertEqual(1, erthNotes().count)
        XCTAssertLessThan(bal(c, "uerth"), pre)

        // ANML with no ERTH note at all: its fee cannot come out of ANML.
        let d = try wallet(chain, "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")
        try await d.sync()
        let an = try d.shieldOutput(denom: "uanml", amount: 1_000_000)
        chain.shield("uanml", an.value, an.pc, an.ciphertext)
        try await d.sync()
        do {
            _ = try await d.send(to: b.address, denom: "uanml", amount: 100_000)
            XCTFail("sent without a fee note")
        } catch is NoteSelection.Insufficient {}
        dump(chain, "singleErthNote")
    }

    /// With PRIVACY_TOML_OUT set, writes every witness the wallet proved as a
    /// nargo Prover.toml, for `nargo execute` against the real circuits.
    func dump(_ chain: FakeChain, _ test: String) {
        guard let out = ProcessInfo.processInfo.environment["PRIVACY_TOML_OUT"] else { return }
        let dir = URL(fileURLWithPath: out)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for (i, w) in chain.prover.allTransfers.enumerated() {
            try? w.proverToml().write(to: dir.appendingPathComponent("\(test)_transfer_\(i).toml"), atomically: true, encoding: .utf8)
        }
        for (i, w) in chain.prover.allMemberships.enumerated() {
            try? w.proverToml().write(to: dir.appendingPathComponent("\(test)_membership_\(i).toml"), atomically: true, encoding: .utf8)
        }
    }
}
