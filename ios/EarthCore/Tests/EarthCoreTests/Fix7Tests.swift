import BigInt
import XCTest
@testable import EarthCore

/// Clients round 7 (chain 48b631c, ORCHARD_DESIGN 17-18), ports Fix7Test.kt:
/// the registration binding's chain id, undelegations that pay out by
/// themselves, one stake vote per validator (four note slots, ten public
/// inputs), the 5x private gas ceiling, send-disabled denoms at every pool
/// edge, the new personhood errors, and nothing ever sent but by the user.
final class Fix7Tests: XCTestCase {
    let alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    let vB = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    let yes = [WeightedVoteOption(option: WeightedVoteOption.yes, weight: "1")]

    func wallet(_ chain: FakeChain, due: @escaping (UInt64) -> Int64? = { _ in nil }) throws -> PrivacyWallet {
        PrivacyWallet(keys: try PrivacyKeys.fromMnemonic(alice), store: .memory(), indexer: chain, chain: chain,
                      reads: FakeReads(chain: chain, due: due), prover: chain.prover, chainID: chain.chainID, roots: chain,
                      now: { [unowned chain] in chain.now })
    }

    func funded(_ chain: FakeChain, _ w: PrivacyWallet, _ amount: UInt64 = 2_000_000) throws {
        let o = try w.shieldOutput(denom: "uerth", amount: 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    func staked(_ chain: FakeChain, due: @escaping (UInt64) -> Int64? = { _ in nil }) async throws -> PrivacyWallet {
        let a = try wallet(chain, due: due)
        for _ in 0 ..< 4 { try funded(chain, a) }
        try await a.sync()
        _ = try await a.delegate(validator: vB, amount: 1_000_000); try await a.sync()
        return a
    }

    func bal(_ w: PrivacyWallet, _ d: String = "uerth") -> UInt64 { w.balances()[d] ?? 0 }

    func assertThrowsAsync<T>(_ body: () async throws -> T, _ check: (Error) -> Bool = { _ in true }, line: UInt = #line) async {
        do {
            _ = try await body()
            XCTFail("expected an error", line: line)
        } catch {
            XCTAssertTrue(check(error), "unexpected error \(error)", line: line)
        }
    }

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
    }

    // MARK: 1. the registration binding names the chain

    func testRegistrationBindingNamesTheChain() async throws {
        let d = Vectors.json["derive"] as! [String: Any]
        XCTAssertEqual("148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159", d["reg_pinned"] as? String)
        let one = PrivacyHash.registrationBinding(chainID: "earth-1", idc: Fr(UInt64(1)), pcAnml: Fr(UInt64(2)), ctAnml: Data("anml".utf8),
                                                  pcErth: Fr(UInt64(3)), ctErth: Data("erth".utf8), affiliate: .zero)
        XCTAssertEqual(d["reg_pinned"] as? String, one.hex)
        let other = PrivacyHash.registrationBinding(chainID: "earth-testnet-1", idc: Fr(UInt64(1)), pcAnml: Fr(UInt64(2)), ctAnml: Data("anml".utf8),
                                                    pcErth: Fr(UInt64(3)), ctErth: Data("erth".utf8), affiliate: .zero)
        XCTAssertNotEqual(one, other)
        // The wallet binds its own chain's id.
        let chain = FakeChain()
        let prep = try await wallet(chain).prepareRegistration(referrer: nil)
        XCTAssertEqual(PrivacyHash.registrationBinding(chainID: chain.chainID, idc: try PrivacyKeys.fromMnemonic(alice).idc, pcAnml: prep.anml.pc,
                                                       ctAnml: prep.anml.ciphertext, pcErth: prep.erth.pc, ctErth: prep.erth.ciphertext, affiliate: .zero),
                       prep.binding)
    }

    // MARK: 2. personhood errors

    func testSwitchSignerAndDailyCapErrorsArePlain() {
        let mismatch = ChainErrors.explain(code: 1127, codespace: "personhood")!
        XCTAssertTrue(mismatch.contains("same passport"), mismatch)
        // A simulate (or the gas service) answers with the registered text.
        XCTAssertEqual(mismatch, ChainErrors.explain(text: "identity switch must be proven under the live registration's document signer"))
        XCTAssertEqual(mismatch, GasGrant.Refused(status: 403, message: "identity switch must be proven under the live registration's document signer").message)
        let cap = ChainErrors.explain(code: 1113, codespace: "personhood")!
        XCTAssertTrue(cap.contains("Try again tomorrow"), cap)
        XCTAssertEqual(cap, ChainErrors.explain(text: "rpc error: daily registration limit reached for this document signer or country"))
        // The codes mean nothing in another module.
        XCTAssertNil(ChainErrors.explain(code: 1127, codespace: "dex"))
    }

    // MARK: 3. undelegations pay out by themselves

    func testUndelegateNamesItsPayoutAndMintsNoStakeNote() async throws {
        let chain = FakeChain()
        let a = try await staked(chain, due: { Int64($0) * 1000 })
        let stakeRows = chain.stakeRows.count
        _ = try await a.undelegate(validator: vB, amount: 400_000)
        let m = try XCTUnwrap(chain.lastMsg as? MsgShieldedUndelegate)
        XCTAssertEqual(32, m.pc.count)
        XCTAssertEqual(NoteCipher.blindCiphertextBytes, m.ciphertext.count)
        XCTAssertTrue(m.stake.spcCiphertext.isEmpty)
        // spc_mint is a throwaway pc of ours: never the payout's pc.
        XCTAssertNotEqual(m.stake.spcMint, m.pc)
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
        dump(chain, "fix7Undelegate")
    }

    /// A payout past one note's worth comes as several notes, one ciphertext at several positions: every one is found.
    func testASplitPayoutIsFoundWhole() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.undelegate(validator: vB, amount: 900_000)
        let pc = try Fr(bytes: (chain.lastMsg as! MsgShieldedUndelegate).pc)
        try await a.sync()
        let before = bal(a)
        chain.payUnbonds { p in [p.value / 3, p.value / 3, p.value - 2 * (p.value / 3)] }
        let txs = chain.txs.count
        try await a.sync()
        XCTAssertEqual(txs, chain.txs.count)
        XCTAssertEqual(1_000_000, bal(a) - before)
        XCTAssertEqual(3, a.notes.filter { $0.note.pc(ownerPK: a.keys.ownerPK) == pc }.count)
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
        // A restored wallet finds the payout by trial decryption alone.
        let restored = try wallet(chain)
        try await restored.sync()
        XCTAssertEqual(a.balances(), restored.balances())
        dump(chain, "fix7SplitPayout")
    }

    func testAFailedOrRefusedUndelegationIsForgotten() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        chain.rejectNext = 1
        await assertThrowsAsync({ try await a.undelegate(validator: self.vB, amount: 100_000) })
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
        chain.failInBlockNext = 1
        await assertThrowsAsync({ try await a.undelegate(validator: self.vB, amount: 100_000) })
        XCTAssertEqual(1, a.pendingUnbonds.count)
        try await a.sync()
        XCTAssertTrue(a.pendingUnbonds.isEmpty)
        XCTAssertTrue(chain.unbondPayouts.isEmpty)
    }

    func testPendingUnbondsSurviveAReset() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.undelegate(validator: vB, amount: 100_000)
        let kept = a.store.state.pendingUnbonds
        try a.store.reset(chainID: chain.chainID)
        XCTAssertEqual(kept, a.store.state.pendingUnbonds)
    }

    // MARK: 4. one stake vote per validator

    func testRoundVoteWeightMatchesTheChain() throws {
        let v = Vectors.json["round_vote_weight"] as! [String: String]
        for (k, want) in v {
            XCTAssertEqual(UInt64(want), try PrivacyWallet.voteWeight(UInt64(k)!), k)
        }
    }

    func testAVoteHasFourSlotsAndTenPublicInputs() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.delegate(validator: vB, amount: 500_000); try await a.sync()
        chain.openProposal(7)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 7, validator: vB, options: yes)
        let m = try XCTUnwrap(chain.lastMsg as? MsgStakeVote)
        XCTAssertEqual(4, m.voteNullifiers.count)
        let vs = try m.voteNullifiers.map { try Fr(bytes: $0) }
        XCTAssertEqual([false, false, true, true], vs.map(\.isZero))
        let w = chain.prover.allVotes.last!
        XCTAssertEqual(10, w.publicInputs().count)
        XCTAssertEqual(vs, w.vnfs)
        let inputs = w.noirInputs()
        for k in ["amount", "rho", "rcm", "pos", "path", "low_value", "low_next_value", "low_next_index", "low_index", "low_path", "vnf"] {
            XCTAssertEqual(4, (inputs[k] as! [Any]).count, k)
        }
        XCTAssertEqual(["0x0", "0x0"], Array((inputs["amount"] as! [String]).suffix(2)))
        XCTAssertEqual(32, ((inputs["path"] as! [[String]])[3]).count)
        XCTAssertEqual(m.weight, try PrivacyWallet.voteWeight(900_000 + 450_000))
        // The msg's own checks: four slots.
        XCTAssertThrowsError(try PrivateTxEngine.withVote(m, vnfs: Array(vs.prefix(3)), proof: Data()))
        dump(chain, "fix7VoteSlots")
    }

    func testAVoteWitnessRefusesWhatTheCircuitWould() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        chain.openProposal(8)
        try await a.sync()
        _ = try await a.stakeVote(proposalID: 8, validator: vB, options: yes)
        let w = chain.prover.allVotes.last!
        // The same note twice; more weight than the notes; five slots.
        XCTAssertThrowsError(try VoteWitness(nk: w.nk, slots: w.slots + w.slots, noteRoot: w.noteRoot, nfRoot: w.nfRoot, asset: w.asset,
                                             weight: w.weight, proposalID: w.proposalID, sighash: w.sighash).check())
        XCTAssertThrowsError(try VoteWitness(nk: w.nk, slots: w.slots, noteRoot: w.noteRoot, nfRoot: w.nfRoot, asset: w.asset,
                                             weight: w.slots.reduce(0) { $0 + $1.amount } + 1, proposalID: w.proposalID, sighash: w.sighash).check())
        XCTAssertThrowsError(try VoteWitness(nk: w.nk, slots: Array(repeating: w.slots[0], count: 5), noteRoot: w.noteRoot, nfRoot: w.nfRoot,
                                             asset: w.asset, weight: w.weight, proposalID: w.proposalID, sighash: w.sighash))
        XCTAssertEqual(VoteWitness.maxNotes, MsgStakeVote.maxVoteNotes)
    }

    /// A 1119 in a block names one note: only that one is final, the vote's others may vote again.
    func testARefusalInABlockSettlesOnlyTheNamedNote() {
        let vnf = Fr(UInt64(77))
        XCTAssertTrue(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier \(vnf.hex.uppercased())", vnf))
        XCTAssertFalse(PrivacyWallet.namesVoteNullifier("this stake note already voted on this proposal: proposal 3, vote nullifier 00", vnf))
        XCTAssertEqual(vnf, PrivacyWallet.usedVoteNullifier(UnsignedTx.TxRejected(code: 1119, log: "vote nullifier \(vnf.hex.uppercased())",
                                                                                   codespace: "shieldedstaking"), [Fr(UInt64(1)), vnf]))
    }

    // MARK: 5. gas

    func testEveryPrivateTxDeclaresWithinFiveTimesItsGas() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.delegate(validator: vB, amount: 500_000); try await a.sync()
        let ns = a.stakeNotes.filter { $0.spendable && $0.denom == PrivacyWallet.derthDenom(vB) }
        _ = try await a.restake(validator: vB, notes: ns, amounts: [1_350_000]); try await a.sync()
        chain.openProposal(9); try await a.sync()
        _ = try await a.stakeVote(proposalID: 9, validator: vB, options: yes); try await a.sync()
        _ = try await a.undelegate(validator: vB, amount: 100_000); try await a.sync()
        XCTAssertFalse(chain.gasRatios.isEmpty)
        // simulate + 10% (at least the fixed headroom): well inside the chain's 5x.
        XCTAssertTrue(chain.gasRatios.allSatisfy { (1.0 ... 1.2).contains($0) }, "\(chain.gasRatios)")
        dump(chain, "fix7Gas")
    }

    func testVoteGasEstimateCountsItsNotes() throws {
        func spec(_ used: Int) throws -> VoteWitnessSpec {
            try VoteWitnessSpec(vnfs: (0 ..< 4).map { $0 < used ? Fr(UInt64($0 + 1)) : .zero }) { _ in throw PrivacyError("unused") }
        }
        let msg = MsgStakeVote(bundle: ShieldedBundle(actions: [], balances: [], bindingSig: Data()), proposalID: 1, validator: vB, options: [], weight: 1)
        let g1 = PrivateTxEngine.estimateGas(msg, Assembled(bundles: [], vote: try spec(1)) { _, _, _ in msg }, txBytes: 0)
        let g4 = PrivateTxEngine.estimateGas(msg, Assembled(bundles: [], vote: try spec(4)) { _, _, _ in msg }, txBytes: 0)
        XCTAssertEqual(3 * PrivateTxEngine.noteGas, g4 - g1)
        XCTAssertEqual(PrivateTxEngine.baseGas + PrivateTxEngine.bundleGas + 250_000 + 2_000_000 + 2 * PrivateTxEngine.noteGas, g1)
    }

    // MARK: 7. send-disabled denoms

    func testSendDisabledIsRefusedOnSwapsAndDelegationWithAClearError() async throws {
        let chain = FakeChain()
        let a = try wallet(chain)
        for _ in 0 ..< 3 { try funded(chain, a) }
        try await a.sync()
        chain.sendDisabled = ["uerth"]
        let sent = chain.txs.count
        do {
            _ = try await a.delegate(validator: vB, amount: 100_000)
            XCTFail("delegated a send-disabled denom")
        } catch {
            let text = try XCTUnwrap(ChainErrors.explain(error))
            XCTAssertTrue(text.contains("private staking"), text)
        }
        await assertThrowsAsync({ try await a.noteSwap(denomIn: "uerth", amountIn: 100_000, denomOut: "uanml", minOut: 1) })
        XCTAssertEqual(sent, chain.txs.count)
        XCTAssertNotNil(ChainErrors.explain(code: 5, codespace: "bank", log: "uerth transfers are currently disabled: send transactions are disabled"))
    }

    // MARK: 8. nothing is sent but by the user

    /// Sync, payouts, pending votes and undelegations: none of it ever broadcasts.
    func testSyncNeverSendsAnything() async throws {
        let chain = FakeChain()
        let a = try await staked(chain)
        _ = try await a.undelegate(validator: vB, amount: 100_000)
        chain.openProposal(10)
        let broadcasts = chain.txs.count
        let sims = chain.simulated
        for _ in 0 ..< 3 { try await a.sync() }
        chain.payUnbonds()
        chain.now += 40 * 86_400
        for _ in 0 ..< 3 { try await a.sync() }
        XCTAssertEqual(broadcasts, chain.txs.count)
        XCTAssertEqual(sims, chain.simulated)
    }
}
