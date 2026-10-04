package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.ChainRoots
import network.erth.wallet.privacy.sync.PrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.PrivateChain
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals

/**
 * What the wallet tests against [FakeChain] share: fixed mnemonics, the
 * chain reads a wallet takes (each swappable), wallet setup, funding and a
 * registration.
 */
abstract class WalletTest {
    protected val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    protected val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    protected val carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"
    protected val receiver = "earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"
    protected val yes = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())

    /** The chain reads [chain] serves; a test replaces the snapshot, the nullifier tree, the lease bounds or payout times. */
    protected fun reads(
        chain: FakeChain,
        snapshot: (Long) -> PrivacyChainReads.Snapshot = chain::snapshotRead,
        nfTree: (Long, Int) -> PrivacyChainReads.NfTreePage = chain::nfTreeRead,
        bounds: () -> PrivacyChainReads.LeaseBounds = chain::leaseBounds,
        due: (Long) -> Long? = { null },
    ) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun leaseBounds() = bounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = snapshot(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = nfTree(start, limit)
        override fun positions() = chain.positionReads()
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validators() = chain.validatorsRead()
        override fun minDelegation() = chain.minDelegation
        override fun unbondDueBy(epoch: Long) = due(epoch)
    }

    /** A wallet of [words] on [chain]; by default the chain is also its indexer, roots and reads. */
    protected fun wallet(
        chain: FakeChain,
        words: String = alice,
        store: PrivacyStore = PrivacyStore.memory(),
        indexer: PrivacyIndexer = chain,
        roots: ChainRoots = chain,
        r: PrivacyChainReads = reads(chain),
        pc: PrivateChain = chain,
    ) = PrivacyWallet(PrivacyKeys.fromMnemonic(words), store, indexer, pc, r, chain.prover, chain.chainId, roots, now = { chain.now })

    protected fun bal(w: PrivacyWallet, d: String = "uerth") = w.balances()[d] ?: 0L

    /** Shields [amount] uerth to [w] (a note the chain mints). */
    protected fun funded(chain: FakeChain, w: PrivacyWallet, amount: Long = 1_000_000) {
        val o = w.shieldOutput("uerth", 0)
        chain.shield("uerth", amount, o.pc, o.ciphertext)
    }

    /** Registers [w] with [passport] (a switch when another wallet holds it), its gas grant shielded first. */
    protected fun register(chain: FakeChain, w: PrivacyWallet, passport: String = "555") {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), passport, Fr.of(77).toBigInteger().toString())
        w.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    /** Delegates to [inner]; a test overrides what it needs. */
    protected open class Wrapped(val inner: PrivacyIndexer) : PrivacyIndexer by inner
}
