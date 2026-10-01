package network.erth.wallet.privacy

import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A wallet driven end to end against [FakeChain]: gas grant, registration,
 * claim, assembly vote, private send and receive, unshield, delegation and a
 * stake vote against a snapshot root, then a second wallet restored from the
 * same mnemonic finding everything again.
 */
class WalletFlowTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    private fun reads(chain: FakeChain, snapshotSize: () -> Long = { chain.noteTree.size }) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, chain.now - 3600, 0, 0)
        override fun epochNumber() = 4L
        override fun snapshot(proposalId: Long) = snapshotSize().let { PrivacyChainReads.Snapshot(chain.noteTree.rootAt(it), it) }
        override fun positions() = emptyList<PrivacyChainReads.Position>()
    }

    private fun wallet(chain: FakeChain, words: String, reads: PrivacyChainReads = reads(chain)) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), PrivacyStore.memory(), chain, chain, reads, chain.prover, chain.chainId, now = { chain.now },
    )

    @Test
    fun endToEnd() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        a.sync()
        assertEquals(WalletSync.IdentityStatus.NONE, a.identityStatus())

        // Registration: prepare, the backend shields gas to pc_gas, register.
        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc)
        a.sync()
        assertEquals(100_000L, a.balances()["uerth"])
        val dsc = Fr.of(77)
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", dsc.toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        a.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, a.identityStatus())
        assertEquals(1_000_000L, a.balances()["uanml"])
        // The gas note paid the fee; the reward is a self-mint found by its public amount.
        assertTrue((a.balances()["uerth"] ?: 0) > 5_000_000)
        assertEquals(Privacy.countryField("DE"), a.store.state.identity!!.country)

        // Too soon to claim: activated today.
        assertEquals(chain.now / 86_400 * 86_400 + 2 * 86_400, a.claimOpensAt())
        chain.now += 2 * 86_400
        a.sync()
        assertEquals(0L, a.claimOpensAt())
        a.claimAnml()
        a.sync()
        assertEquals(2_000_000L, a.balances()["uanml"])
        assertTrue(a.claimedToday())

        // An assembly vote.
        chain.now += 7200
        a.voteProposal(5, yes = true)
        assertEquals(5L to 1, chain.votes.last())

        // Private send to bob, who finds it.
        val b = wallet(chain, bob)
        a.sync()
        a.send(b.address, "uanml", 700_000, "hi".toByteArray())
        a.sync(); b.sync()
        assertEquals(1_300_000L, a.balances()["uanml"])
        assertEquals(700_000L, b.balances()["uanml"])
        assertEquals("hi", String(b.notes.first { it.note.denom == "uanml" }.note.memo))

        // Unshield ERTH to an address.
        val before = a.balances()["uerth"]!!
        a.unshield("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls", "uerth", 1_000_000)
        a.sync()
        assertEquals(1_000_000L, chain.unshielded["earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"])
        assertTrue(a.balances()["uerth"]!! < before - 1_000_000)

        // Stake, then vote with the derth against a snapshot taken right after.
        a.delegate(validator, 2_000_000)
        a.sync()
        val derth = PrivacyWallet.derthDenom(validator)
        assertEquals(1_800_000L, a.balances()[derth])
        val snap = chain.noteTree.size
        val fresh = wallet(chain, alice, reads(chain) { snap })
        fresh.sync()
        // A later note moves the tree past the snapshot.
        chain.shield("uerth", 1, Fr.of(5))
        fresh.sync()
        val opts = listOf(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1").build())
        fresh.stakeVote(9, fresh.notes.first { it.note.denom == derth && it.unspent }, opts)
        fresh.sync()
        assertEquals(1_800_000L, fresh.balances()[derth])

        // A wallet restored from the mnemonic alone sees the same balances,
        // the self-mints (gas, reward, derth) included.
        val restored = wallet(chain, alice)
        restored.sync()
        assertEquals(fresh.balances(), restored.balances())
        assertTrue(chain.simulated > 0)

        dump(chain)
    }

    /**
     * With PRIVACY_TOML_OUT set, writes every witness the wallet proved as a
     * nargo Prover.toml, for `nargo execute` against the real circuits.
     */
    private fun dump(chain: FakeChain) {
        val out = System.getenv("PRIVACY_TOML_OUT") ?: return
        chain.prover.allTransfers.forEachIndexed { i, w -> File(out, "transfer_$i.toml").apply { parentFile.mkdirs() }.writeText(w.proverToml()) }
        chain.prover.allMemberships.forEachIndexed { i, w -> File(out, "membership_$i.toml").writeText(w.proverToml()) }
    }
}
