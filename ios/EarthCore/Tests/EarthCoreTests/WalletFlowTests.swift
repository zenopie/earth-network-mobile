import BigInt
import XCTest
@testable import EarthCore

/// Two wallets driven end to end against `FakeChain` on bundles and the stake
/// tree (ports WalletFlowTest.kt): gas grant, registration, claim, assembly
/// vote, private sends of any number of notes and assets, unshields, swaps,
/// private LP add and remove, delegate / restake / undelegate (paid out), stake
/// votes, Groundworks positions, the personhood paths, and wallets restored
/// from their mnemonics finding everything again (stake notes and positions
/// included).
///
/// With PRIVACY_TOML_OUT=<dir>, every witness proved is written as
/// <dir>/{action,stake,membership}/<test>_<i>/Prover.toml, for `nargo
/// execute` against the real circuits.
final class WalletFlowTests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    let validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    var derth: String { PrivacyWallet.derthDenom(validator) }
    func reads(_ chain: FakeChain) -> FakeReads { FakeReads(chain: chain) }


    func wallet(_ chain: FakeChain, _ words: String, reads r: FakeReads? = nil, indexer: PrivacyIndexer? = nil,
                store: PrivacyStore = .memory()) throws -> PrivacyWallet {
        return PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(words), store: store, indexer: indexer ?? chain, chain: chain,
                             reads: r ?? reads(chain), prover: chain.prover, chainID: chain.chainID, roots: chain,
                             now: { [unowned chain] in chain.now })
    }

    func signals(_ prep: PrivacyWallet.RegistrationPrep) -> [String] {
        ["261001", prep.binding.bigUInt.description, "123456789", Fr(UInt64(77)).bigUInt.description]
    }

    func bal(_ w: PrivacyWallet, _ d: String) -> UInt64 { w.balances()[d] ?? 0 }

    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]

    func shieldTo(_ chain: FakeChain, _ w: PrivacyWallet, _ denom: String, _ value: UInt64) throws {
        let o = try w.shieldOutput(denom: denom, amount: value)
        chain.shield(denom, value, o.pc, o.ciphertext)
    }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

    func testEndToEnd() async throws {
        let chain = FakeChain()
        let a = try wallet(chain, alice)
        try await a.sync()
        XCTAssertEqual(.none, a.identityStatus())

        // Registration: prepare, the backend shields gas to pc_gas, register.
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        XCTAssertEqual(100_000, bal(a, "uerth"))
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        try await a.sync()
        XCTAssertEqual(.live, a.identityStatus())
        XCTAssertEqual(1_000_000, bal(a, "uanml"))
        // The gas note paid the fee bundle; the reward is found by its v2 ciphertext and public amount.
        XCTAssertGreaterThan(bal(a, "uerth"), 5_000_000)
        XCTAssertEqual(PrivacyHash.countryField("DE"), a.snapshot.identity?.country)
        // Every bundle is padded to at least two actions.
        XCTAssertTrue(chain.actionCounts.allSatisfy { $0 >= 2 })

        // Too soon to claim: activated today.
        XCTAssertEqual(chain.now / 86_400 * 86_400 + 2 * 86_400, a.claimOpensAt())
        chain.now += 2 * 86_400
        try await a.sync()
        XCTAssertEqual(0, a.claimOpensAt())
        _ = try await a.claimAnml()
        try await a.sync()
        XCTAssertEqual(2_000_000, bal(a, "uanml"))
        XCTAssertTrue(a.claimedToday())

        // An assembly vote.
        chain.now += 7200
        _ = try await a.voteProposal(proposalID: 5, yes: true)
        XCTAssertEqual(5, chain.votes.last?.0)
        XCTAssertEqual(1, chain.votes.last?.1)

        // Private send to bob, who finds it; ANML and its ERTH fee in one bundle.
        let b = try wallet(chain, bob)
        try await a.sync()
        _ = try await a.send(to: b.address, denom: "uanml", amount: 700_000, memo: Data("hi".utf8))
        try await a.sync(); try await b.sync()
        XCTAssertEqual(1_300_000, bal(a, "uanml"))
        XCTAssertEqual(700_000, bal(b, "uanml"))
        XCTAssertEqual("hi", String(decoding: b.notes.first { $0.note.denom == "uanml" }!.note.memo, as: UTF8.self))

        // Unshield ERTH to an address.
        let before = bal(a, "uerth")
        _ = try await a.unshield(receiver: receiver, denom: "uerth", amount: 1_000_000)
        try await a.sync()
        XCTAssertEqual(1_000_000, chain.unshieldedTo(receiver))
        XCTAssertLessThan(bal(a, "uerth"), before - 1_000_000)

        // Stake: the quoted derth (9/10 at the fake's rate, less the drift margin) in a note of ours, its input padded.
        _ = try await a.delegate(validator: validator, amount: 2_000_000)
        try await a.sync()
        let q1 = (chain.lastMsg as! MsgShieldedDelegate).derth
        XCTAssertEqual(1_800_000 - (1_800_000 * PrivacyWallet.creditMarginPPM + 999_999) / 1_000_000, q1)
        XCTAssertEqual(q1, bal(a, derth))
        XCTAssertEqual(1, a.stakeNotes.count)
        // Owner-locked: stake cannot be sent or unshielded.
        await assertThrowsAsync({ try await a.send(to: b.address, denom: self.derth, amount: 1) }) { $0 is PrivacyError }
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: self.derth, amount: 1) }) { $0 is PrivacyError }

        // A stake vote against a snapshot taken right after: the note proves
        // itself unspent at the snapshot and is not spent (ORCHARD_DESIGN 8.5).
        chain.openProposal(9)
        let fresh = try wallet(chain, alice)
        try await fresh.sync()
        // A top-up after the snapshot merges into the note (spending it).
        _ = try await fresh.delegate(validator: validator, amount: 100_000)
        try await fresh.sync()
        let q2 = (chain.lastMsg as! MsgShieldedDelegate).derth
        let items9 = try await fresh.stakeVoteItems(proposalID: 9)
        _ = try await fresh.castStakeVote(proposalID: 9, item: try XCTUnwrap(items9.first), options: yes)
        try await fresh.sync()
        // The old note votes the value it held at the snapshot; the merged one is not in it.
        XCTAssertEqual(1, chain.stakeVotes.count)
        XCTAssertEqual(9, chain.stakeVotes[0].0)
        XCTAssertEqual(validator, chain.stakeVotes[0].1)
        XCTAssertEqual(try PrivacyWallet.voteWeight(q1), chain.stakeVotes[0].2)
        XCTAssertEqual(q1 + q2, bal(fresh, derth))
        XCTAssertEqual(1, fresh.stakeNotes.filter(\.unspent).count)
        let left = try await fresh.stakeVoteItems(proposalID: 9)
        XCTAssertTrue(left.isEmpty)

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
        let prep = try await a.prepareRegistration(referrer: nil)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        try await a.sync()
        _ = try await a.register(prep, proof: Data(count: 14_656), publicSignals: signals(prep), signatureAlgorithm: "lean_poa", dscDer: Data(count: 10))
        chain.now += 2 * 86_400
        try await a.sync()
        _ = try await a.claimAnml()
        try await a.sync()
        return a
    }

    /// The dex, LP, staking, positions and personhood paths with two wallets.
    func testDexStakingPositionsAndPersonhood() async throws {
        let chain = FakeChain()
        let a = try await registered(chain, alice)
        let b = try wallet(chain, bob)
        try await b.sync()
        XCTAssertEqual(2_000_000, bal(a, "uanml"))
        let pool = { ["uanml": SwapMath.Reserves(erth: chain.poolErth, token: chain.poolAnml)] }

        // ANML -> ERTH: the quote is the chain's, the fee comes from an ERTH note (one fee rule).
        let erth0 = bal(a, "uerth")
        let q = SwapMath.route(pools: pool(), hub: "uerth", denomIn: "uanml", amountIn: 600_000, denomOut: "uerth", feePercent: chain.swapFee)!
        _ = try await a.noteSwap(denomIn: "uanml", amountIn: 600_000, denomOut: "uerth", minOut: UInt64(SwapMath.withSlippage(q.amountOut, bps: 100)))
        try await a.sync()
        XCTAssertEqual(1_400_000, bal(a, "uanml"))
        let fee = Int64(q.amountOut) - Int64(bal(a, "uerth") - erth0)
        XCTAssertTrue((1000 ... 10_000).contains(fee), "fee from the bundle: \(fee)")

        // ERTH -> ANML by bob from the one note he was sent: amount and fee
        // from the same note, the change back in the same bundle.
        _ = try await a.send(to: b.address, denom: "uerth", amount: 2_000_000)
        try await a.sync(); try await b.sync()
        XCTAssertEqual(1, b.notes.filter { $0.note.denom == "uerth" && $0.unspent }.count)
        let bq = SwapMath.route(pools: pool(), hub: "uerth", denomIn: "uerth", amountIn: 1_000_000, denomOut: "uanml", feePercent: chain.swapFee)!
        _ = try await b.noteSwap(denomIn: "uerth", amountIn: 1_000_000, denomOut: "uanml", minOut: UInt64(SwapMath.withSlippage(bq.amountOut, bps: 50)))
        try await b.sync()
        XCTAssertEqual(UInt64(bq.amountOut), bal(b, "uanml"))
        XCTAssertTrue((900_000 ..< 1_000_000).contains(bal(b, "uerth")))

        // A swap paid to someone else: bob opens its value-blind (v2) ciphertext.
        let bErth = bal(b, "uerth")
        let gq = SwapMath.route(pools: pool(), hub: "uerth", denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", feePercent: chain.swapFee)!
        _ = try await a.noteSwap(denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", minOut: UInt64(SwapMath.withSlippage(gq.amountOut, bps: 100)),
                                 to: b.address)
        try await a.sync(); try await b.sync()
        let paid = chain.notes.last!
        XCTAssertEqual(177, paid.ciphertext.count)
        XCTAssertEqual("\(bal(b, "uerth") - bErth)uerth", paid.amount)
        XCTAssertEqual(1_300_000, bal(a, "uanml"))

        // A bound the pool cannot meet fails before anything is spent.
        let before = a.balances()
        await assertThrowsAsync({ try await a.noteSwap(denomIn: "uanml", amountIn: 100_000, denomOut: "uerth", minOut: 10_000_000) })
        try await a.sync()
        XCTAssertEqual(before, a.balances())

        // Private LP: one bundle (ANML + ERTH legs), shares as a note.
        let erthBefore = bal(a, "uerth")
        _ = try await a.addLiquidityShielded(poolID: 1, token: "uanml", tokenAmount: 400_000, erthAmount: 2_000_000, minShares: "1")
        try await a.sync()
        let shares = bal(a, "dexlp/1")
        XCTAssertGreaterThan(shares, 0)
        XCTAssertEqual([1: shares], a.lpShares())
        XCTAssertTrue((900_000 ... 900_010).contains(bal(a, "uanml")))
        let spent = erthBefore - bal(a, "uerth")
        XCTAssertTrue((800_000 ... 810_000).contains(spent), "the unused ERTH is refunded: spent \(spent)")
        // Shares cannot be unshielded, only withdrawn.
        await assertThrowsAsync({ try await a.unshield(receiver: self.receiver, denom: "dexlp/1", amount: shares) }) { $0 is PrivacyError }
        // Withdraw half; both legs are notes at maturity.
        let e0 = bal(a, "uerth"), t0 = bal(a, "uanml")
        _ = try await a.removeLiquidityShielded(poolID: 1, token: "uanml", shares: shares / 2)
        try await a.sync()
        XCTAssertEqual(shares - shares / 2, bal(a, "dexlp/1"))
        chain.matureWithdrawals()
        try await a.sync()
        XCTAssertGreaterThan(bal(a, "uanml"), t0)
        XCTAssertGreaterThan(bal(a, "uerth"), e0 - 10_000)

        // Staking: delegate twice without a sync between (as two devices
        // would): each pads its input, two notes; the user merges them.
        _ = try await a.delegate(validator: validator, amount: 1_000_000)
        _ = try await a.delegate(validator: validator, amount: 1_000_000)
        try await a.sync()
        let held0 = bal(a, derth)
        XCTAssertEqual(2 * (900_000 - 9), held0)
        XCTAssertEqual(2, a.stakeNotes.filter(\.spendable).count)
        XCTAssertEqual([derth: 2], a.stakeMergeable())
        _ = try await a.mergeStake(denom: derth)
        try await a.sync()
        XCTAssertEqual(held0, bal(a, derth))
        XCTAssertEqual(1, a.stakeNotes.filter(\.spendable).count)
        XCTAssertTrue(a.stakeMergeable().isEmpty)
        // One note per validator: the next delegation merges into it.
        _ = try await a.delegate(validator: validator, amount: 100_000); try await a.sync()
        let total = bal(a, derth)
        XCTAssertEqual(held0 + 90_000 - 1, total)
        XCTAssertEqual(1, a.stakeNotes.filter(\.spendable).count)

        // Groundworks: lock a position (owner tag), re-split, vote, unlock.
        _ = try await a.lockPosition(validator: validator, amount: 1_000_000, splits: [2: 60, 5: 40])
        try await a.sync()
        XCTAssertEqual(total - 1_000_000, bal(a, derth))
        let mine = try await a.positions()
        XCTAssertEqual(1, mine.count)
        let (pos, tag) = (mine[0].position, mine[0].counter)
        XCTAssertEqual([2: 60, 5: 40], pos.splits)
        _ = try await a.updatePosition(pos, counter: tag, splits: [2: 100])
        XCTAssertEqual([2: 100], chain.positions[pos.id]!.splits)
        _ = try await a.positionVote(try await a.positions()[0].position, counter: tag, proposalID: 9, options: yes)
        XCTAssertEqual(9, chain.positionVotes.first?.1)
        // Only its owner can move it.
        await assertThrowsAsync({ try await b.updatePosition(pos, counter: 0, splits: [2: 100]) }) { $0 is PrivacyError }
        // Unlocking merges the position's derth back into the note; the fee bundle records the closed counter.
        _ = try await a.unlockPosition(try await a.positions()[0].position, counter: tag)
        try await a.sync()
        let gone = try await a.positions()
        XCTAssertTrue(gone.isEmpty)
        XCTAssertEqual(total, bal(a, derth))
        XCTAssertEqual(1, a.stakeNotes.filter(\.spendable).count)
        let restoredA = try wallet(chain, alice)
        try await restoredA.sync()
        XCTAssertEqual(tag, restoredA.store.state.closedOtagMax)

        // Stake votes: the derth note from before the snapshot, one vote per validator, its rounded amount.
        try await a.sync()
        chain.openProposal(11)
        let weight = try await a.stakeVoteWeight(proposalID: 11, positions: [])
        let held = a.stakeNotes.filter { $0.spendable && $0.denom == derth }
        XCTAssertEqual(1, held.count)
        let rounded = try PrivacyWallet.voteWeight(held.reduce(UInt64(0)) { $0 + $1.amount })
        XCTAssertEqual(held.count, weight.notes)
        XCTAssertEqual(liveValue(chain, try PrivacyWallet.parseDerth(derth), rounded), weight.uerth)
        var voted: [TxResult] = []
        for item in try await a.stakeVoteItems(proposalID: 11) {
            if let r = try await a.castStakeVote(proposalID: 11, item: item, options: yes) { voted.append(r) }
        }
        XCTAssertEqual(1, voted.count)
        try await a.sync()
        XCTAssertEqual(total, bal(a, derth))
        XCTAssertEqual(1, chain.stakeVotes.count)
        XCTAssertEqual(rounded, chain.stakeVotes[0].2)
        XCTAssertEqual([held.count], chain.stakeVoteSlots)

        // A handle: claimed by a fresh registrant at once, naming this wallet's shielded address.
        chain.now += 31 * 86_400
        try await a.sync()
        _ = try await a.bindHandle("alice")
        XCTAssertEqual(a.address.encode(), chain.handles["alice"]?.address)
        XCTAssertEqual("alice", a.snapshot.handle)
        _ = try await a.setCaretaker(split: [1: 100])
        XCTAssertEqual([[1: 100]], Array(chain.caretakerVotes.values))

        // Removal ballot: open one, vote in it.
        _ = try await a.proposeRemoval(optionID: 3)
        _ = try await a.voteRemoval(optionID: 3, yes: true)
        XCTAssertEqual(3, chain.removalVotes.first?.0)

        // Unstake (the note, change back as a created stake note): the msg
        // names a pool note of ours; at maturity the chain pays it there by
        // itself, and the wallet sends nothing more.
        try await a.sync()
        _ = try await a.undelegate(validator: validator, amount: 1_000_000)
        try await a.sync()
        XCTAssertEqual(total - 1_000_000, bal(a, derth))
        let u = a.pendingUnbonds[0]
        XCTAssertEqual(1, a.pendingUnbonds.count)
        XCTAssertEqual([1_111_111, 4, 1], [u.value, u.epoch, u.payoutID])
        let erthPre = bal(a, "uerth")
        let txsBefore = chain.txs.count
        chain.payUnbonds()
        try await a.sync()
        XCTAssertEqual(txsBefore, chain.txs.count)
        XCTAssertEqual(1_111_111, bal(a, "uerth") - erthPre)
        XCTAssertTrue(a.pendingUnbonds.isEmpty)

        // Both wallets, restored from their mnemonics, find everything again:
        // pool self-mints, stake mints (by spc), created stake notes (by
        // their stake ciphertext) and share notes.
        for (w, words) in [(a, alice), (b, bob)] {
            let restored = try wallet(chain, words)
            try await restored.sync()
            XCTAssertEqual(w.balances(), restored.balances())
        }
        dump(chain, "dex")
    }

    /// Any number of notes: a payment spending 16 small notes in one bundle
    /// (the old circuit's 3-note limit is gone), a multi-asset bundle, Max
    /// unshielding every note a bundle carries with the fee from the amount,
    /// and merge only past max_actions_per_bundle.
    func testManyNotesAndMax() async throws {
        let chain = FakeChain()
        let c = try wallet(chain, alice)
        let b = try wallet(chain, bob)
        try await c.sync(); try await b.sync()
        for _ in 0 ..< 20 { try shieldTo(chain, c, "uerth", 100_000) }
        for _ in 0 ..< 3 { try shieldTo(chain, c, "uanml", 50_000) }
        try await c.sync()

        // 1.495 ERTH and its fee need 16 notes: one bundle of 16 actions.
        _ = try await c.send(to: b.address, denom: "uerth", amount: 1_495_000)
        XCTAssertEqual(16, chain.actionCounts.last)
        try await c.sync(); try await b.sync()
        XCTAssertEqual(1_495_000, bal(b, "uerth"))

        // ANML from three notes and ERTH, both to bob, the fee in the same bundle.
        _ = try await c.send(to: b.address, denom: "uanml", amount: 120_000)
        try await c.sync(); try await b.sync()
        XCTAssertEqual(120_000, bal(b, "uanml"))
        XCTAssertEqual(30_000, bal(c, "uanml"))

        // Max unshield: every spendable note one bundle carries, the fee out of it.
        let max = ShieldMove.maxUnshield(c.notes, maxNotes: chain.maxActions)
        XCTAssertEqual(bal(c, "uerth"), max)
        XCTAssertEqual(max, c.snapshot.unshieldableErth)
        _ = try await c.unshield(receiver: receiver, denom: "uerth", amount: max, feeFromAmount: true)
        try await c.sync()
        XCTAssertEqual(0, bal(c, "uerth"))
        XCTAssertTrue((max - 10_000 ..< max).contains(chain.unshieldedTo(receiver)))

        // More notes than a bundle carries: the payment refuses, a merge fixes it.
        chain.maxActions = 4
        for _ in 0 ..< 6 { try shieldTo(chain, c, "uerth", 10_000) }
        try await c.sync()
        await assertThrowsAsync({ try await c.send(to: b.address, denom: "uerth", amount: 50_000) }) { $0 is NoteSelection.Insufficient }
        _ = try await c.merge(denom: "uerth")
        try await c.sync()
        XCTAssertEqual(3, c.notes.filter { $0.note.denom == "uerth" && $0.unspent }.count)
        _ = try await c.send(to: b.address, denom: "uerth", amount: 50_000)
        try await c.sync()

        // ANML with no ERTH at all: its fee cannot come out of ANML.
        let d = try wallet(chain, "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong")
        try await d.sync()
        try shieldTo(chain, d, "uanml", 1_000_000)
        try await d.sync()
        await assertThrowsAsync({ try await d.send(to: b.address, denom: "uanml", amount: 100_000) }) { $0 is NoteSelection.Insufficient }
        dump(chain, "manyNotes")
    }

    /// With PRIVACY_TOML_OUT set, writes every witness the wallet proved as a
    /// nargo Prover.toml, for `nargo execute` against the real circuits.
    func dump(_ chain: FakeChain, _ test: String) {
        guard let out = ProcessInfo.processInfo.environment["PRIVACY_TOML_OUT"] else { return }
        func write(_ kind: String, _ i: Int, _ toml: String) {
            let dir = URL(fileURLWithPath: out).appendingPathComponent(kind).appendingPathComponent("\(test)_\(i)")
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try? toml.write(to: dir.appendingPathComponent("Prover.toml"), atomically: true, encoding: .utf8)
        }
        for (i, w) in chain.prover.allActions.enumerated() { write("action", i, w.proverToml()) }
        for (i, w) in chain.prover.allStakes.enumerated() { write("stake", i, w.proverToml()) }
        for (i, w) in chain.prover.allVotes.enumerated() { write("vote", i, w.proverToml()) }
        for (i, w) in chain.prover.allMemberships.enumerated() { write("membership", i, w.proverToml()) }
    }
}

/// Records the pauses a wallet asked for (thread-safe: the wallet calls it from its own task).
final class Pauses: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [UInt64] = []
    func add(_ v: UInt64) { lock.lock(); values.append(v); lock.unlock() }
    var all: [UInt64] { lock.lock(); defer { lock.unlock() }; return values }
}
