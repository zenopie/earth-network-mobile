package network.erth.wallet.privacy

import network.erth.wallet.privacy.handles.HandleDirectory
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.handles.Handles
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handles end to end against [FakeChain] (claim,
 * renew, change, release, move; the whole-directory lookup), paying a handle,
 * a registration referred by a handle, an identity switch that moves its
 * handle and caretaker vote first, and the predecessor bounds of every
 * membership statement.
 */
class HandlesTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    private fun reads(chain: FakeChain) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun leaseBounds() = chain.leaseBounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
        override fun debtTree(start: Long, limit: Int) = chain.debtTreeRead(start, limit)
        override fun validators() = chain.validatorsRead()
        override fun minDelegation() = chain.minDelegation
    }

    private fun wallet(chain: FakeChain, words: String, store: PrivacyStore = PrivacyStore.memory()) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), store, chain, chain, reads(chain), chain.prover, chain.chainId, chain, now = { chain.now },
    )

    private fun bal(w: PrivacyWallet, d: String = "uerth") = w.balances()[d] ?: 0L

    /** Registers [w] with [passport] (a switch when another wallet holds it), referred by [referrer]. */
    private fun register(chain: FakeChain, w: PrivacyWallet, passport: String = "123456789", referrer: PrivacyWallet.Referrer? = null): PrivacyWallet.RegistrationPrep {
        val prep = w.prepareRegistration(referrer)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), passport, Fr.of(77).toBigInteger().toString())
        w.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
        return prep
    }

    private fun entry(chain: FakeChain, h: String): HandleEntry? = chain.handleDirectory().lookup(h)

    @Test
    fun handleLifecycle() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        register(chain, a, passport = "111")
        register(chain, b, passport = "222")
        // A fresh registrant (predecessor_at 0) claims at once.
        assertEquals(0L, a.store.state.identity!!.predecessorAt)
        a.bindHandle("alice")
        val e = entry(chain, "alice")!!
        assertEquals(HandleEntry.LIVE, e.status)
        assertEquals(a.address.encode(), e.address)
        assertEquals(chain.now + chain.handleLease, e.expiresAt)
        assertEquals(e.expiresAt + chain.handleRenewal, e.renewalUntil)
        // The claim proved the lease bound, not "no bound".
        val claim = chain.prover.allMemberships.last()
        assertTrue(claim.maxPredecessor < chain.now - chain.handleLease - 86_400)
        assertEquals(Privacy.NO_BOUND, claim.maxActivation)

        // Taken: another human cannot claim it (1122).
        val taken = runCatching { b.bindHandle("alice") }.exceptionOrNull()
        assertTrue("$taken", taken?.message.orEmpty().contains("1122"))

        // Renewal: a year from now, the address may change.
        chain.now += 100 * 86_400
        a.sync()
        a.bindHandle("alice")
        assertEquals(chain.now + chain.handleLease, entry(chain, "alice")!!.expiresAt)

        // A change frees the old handle at once: bob claims it in the next block.
        a.bindHandle("alice-two")
        assertEquals(null, entry(chain, "alice"))
        assertEquals("alice-two", a.store.state.handle)
        b.sync()
        b.bindHandle("alice")
        assertEquals(b.address.encode(), entry(chain, "alice")!!.address)

        // Release: gone at once; a holder of none claims again (fresh: no wait).
        a.sync()
        a.releaseHandle()
        assertEquals(null, entry(chain, "alice-two"))
        assertEquals("", a.store.state.handle)
        a.sync()
        a.bindHandle("alice-three")
        assertNotNull(entry(chain, "alice-three"))

        // Lapse: the entry stays reserved for the renewal period, then is free.
        chain.now += chain.handleLease + 1
        assertEquals(HandleEntry.RENEWAL, entry(chain, "alice-three")!!.status)
        chain.now += chain.handleRenewal
        assertEquals(null, entry(chain, "alice-three"))
        dumpWitnesses(chain, "handleLifecycle")
    }

    @Test
    fun payAHandleFromTheWholeDirectory() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        register(chain, a, passport = "111")
        register(chain, b, passport = "222")
        a.bindHandle("alice")
        a.sync(); b.sync()
        val dir = chain.handleDirectory()
        chain.handleAsks.clear()
        val r = dir.resolveForPayment("@Alice")
        assertTrue(r is HandleDirectory.Resolution.Payable)
        r as HandleDirectory.Resolution.Payable
        assertEquals(a.address, r.address)
        // Never one handle: the indexer's stream from 0, and the chain's directory from the first.
        assertTrue(chain.handleAsks.isNotEmpty())
        assertTrue(chain.handleAsks.all { it == "indexer:0" || it == "chain:" })
        val before = bal(a)
        b.send(r.address, "uerth", 1_234_000)
        a.sync()
        assertEquals(before + 1_234_000, bal(a))

        // An indexer naming another address for the handle is caught by the chain's directory.
        chain.forgeHandleAddress = b.address.encode()
        val forged = chain.handleDirectory().resolveForPayment("alice")
        assertTrue("$forged", forged is HandleDirectory.Resolution.NotPayable)
        chain.forgeHandleAddress = null

        // A handle that lapsed (renewal period) or was never claimed pays nobody.
        assertTrue(chain.handleDirectory().resolveForPayment("nobody") is HandleDirectory.Resolution.NotPayable)
        assertTrue(chain.handleDirectory().resolveForPayment("not a handle!") is HandleDirectory.Resolution.NotPayable)
        chain.now += chain.handleLease + 10
        assertTrue(chain.handleDirectory().resolveForPayment("alice") is HandleDirectory.Resolution.NotPayable)
        assertEquals("alice", Handles.parse(" @ALICE "))
        assertTrue(Handles.looksLikeHandle("@alice") && Handles.looksLikeHandle("alice") && !Handles.looksLikeHandle("earth1abc"))
        assertTrue(!Handles.valid("-ab") && !Handles.valid("ab") && !Handles.valid("a_b") && Handles.valid("a-b"))
    }

    @Test
    fun registrationReferredByAHandle() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "111")
        a.bindHandle("alice")
        a.sync()
        val before = bal(a)
        val b = wallet(chain, bob)
        val res = chain.handleDirectory().resolveForPayment("@alice") as HandleDirectory.Resolution.Payable
        val prep = register(chain, b, passport = "222", referrer = PrivacyWallet.Referrer(res.entry.handle, res.address))
        // The binding commits to the handle alone: the chain makes the referral note.
        assertEquals(
            Privacy.registrationBinding(chain.chainId, b.keys.idc, prep.anml.pc, prep.anml.ciphertext, prep.erth.pc, prep.erth.ciphertext, Privacy.affiliateField("alice")),
            prep.binding,
        )
        // Minted to the handle's owner_pk with the opening derived from the passport nullifier and the leaf.
        val leaf = b.store.state.identity!!.leafIndex
        val (rho, rcm) = Privacy.referralOpening(Fr.of(java.math.BigInteger("222")), leaf)
        assertEquals("alice" to Privacy.pc(a.keys.ownerPk, rho, rcm), chain.referralNotes.single())
        // The referrer's wallet finds it in the stream (no ciphertext: by owner_pk, cm checked), privately.
        a.sync()
        assertEquals(before + 5_000_000, bal(a))
        val note = a.notes.single { it.position == chain.referralPositions.single() }
        assertEquals(rho, note.note.rho)
        assertEquals(rcm, note.note.rcm)
        // ... and can spend it (its nullifier is the usual one: nk, rho, position).
        assertEquals(Privacy.nf(a.keys.nk, rho, note.position), note.nf)
        // Nobody else's wallet takes it.
        b.sync()
        assertTrue(b.notes.none { it.position == note.position })

        // A lapsed handle is refused (1121); a registration cannot name its own wallet.
        chain.now += chain.handleLease + 10
        val c = wallet(chain, carol)
        val cprep = c.prepareRegistration(PrivacyWallet.Referrer("alice", a.address))
        chain.shield("uerth", 100_000, cprep.gas.pc, cprep.gas.ciphertext)
        c.sync()
        val refused = runCatching {
            c.register(cprep, ByteArray(14_656), listOf("261001", cprep.binding.toBigInteger().toString(), "333", Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        }.exceptionOrNull()
        assertTrue("$refused", refused?.message.orEmpty().contains("1121"))
        assertThrows(IllegalArgumentException::class.java) { a.prepareRegistration(PrivacyWallet.Referrer("alice", a.address)) }
    }

    @Test
    fun switchMovesTheHandleAndCaretakerVoteFirst() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "999")
        a.bindHandle("alice")
        a.sync()
        a.setCaretaker(mapOf(1L to 100L))
        a.sync()
        val split = a.store.state.caretakerSplit
        val exp = a.caretakerExpiresAt()
        assertTrue(a.caretakerLive())

        // The new wallet on this phone: its handle- and caretaker-scope nullifiers.
        val bKeys = PrivacyKeys.fromMnemonic(bob)
        a.moveHandle(a.newOwner(bKeys, Privacy.handleScope()))
        a.sync()
        a.moveCaretaker(a.newOwner(bKeys, Privacy.caretakerScope()))
        a.sync()
        assertTrue(a.store.state.handleMovedOut && a.store.state.caretakerMovedOut)
        assertEquals(Privacy.scopeNullifier(bKeys.idSecret, Privacy.handleScope()), chain.handles.getValue("alice").nullifier)
        assertEquals(exp, chain.caretakerExpiry.getValue(Privacy.scopeNullifier(bKeys.idSecret, Privacy.caretakerScope())))
        // The old identity can never hold them again.
        assertThrows(IllegalStateException::class.java) { a.setCaretaker(mapOf(2L to 100L)) }
        assertThrows(IllegalStateException::class.java) { a.bindHandle("alice-again") }

        // The switch: the same passport from the new wallet. Its leaf has a predecessor.
        val b = wallet(chain, bob)
        b.adoptMoved("alice", split, exp)
        register(chain, b, passport = "999")
        a.sync()
        assertEquals(WalletSync.IdentityStatus.ZEROED, a.identityStatus())
        val id = b.store.state.identity!!
        assertEquals(id.activatedAt, id.predecessorAt)
        // What moved is renewed and refreshed at once (no bound: it holds them).
        b.bindHandle("alice")
        assertEquals(b.address.encode(), entry(chain, "alice")!!.address)
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        b.sync()
        b.setCaretaker(mapOf(1L to 50L, 2L to 50L))
        assertEquals(mapOf(1L to 50L, 2L to 50L), chain.caretakerVotes[Privacy.scopeNullifier(bKeys.idSecret, Privacy.caretakerScope())])
        dumpWitnesses(chain, "switchMoves")
    }

    @Test
    fun predecessorBounds() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "777")
        // A switch to a wallet that moved nothing: its leaf names the switch.
        chain.now += 2 * 86_400
        val c = wallet(chain, carol)
        register(chain, c, passport = "777")
        // A switch pays nothing: fees from a shield.
        c.shieldOutput("uerth", 5_000_000).let { chain.shield("uerth", 5_000_000, it.pc, it.ciphertext) }
        c.sync()
        val pred = c.store.state.identity!!.predecessorAt
        assertEquals(c.store.state.identity!!.activatedAt, pred)
        // No new handle, no new caretaker vote until anything the old one held has lapsed.
        val h = runCatching { c.bindHandle("carol") }.exceptionOrNull()
        assertTrue("$h", h is PrivacyWallet.NotYet && h.waitSeconds > chain.handleLease)
        val v = runCatching { c.setCaretaker(mapOf(1L to 100L)) }.exceptionOrNull()
        assertTrue("$v", v is PrivacyWallet.NotYet && v.waitSeconds > chain.caretakerLease)
        // No ballot vote on a ballot opened before the switch (opened - 86400 bound).
        assertTrue(runCatching { c.voteProposal(5, yes = true) }.exceptionOrNull() is PrivacyWallet.NotYet)
        // Claims bound the activation only: the switched identity claims from the day after next.
        chain.now += 2 * 86_400
        c.sync()
        c.claimAnml()
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        // A day later the ballot bound has passed the switch.
        c.voteProposal(6, yes = true)
        // Past the caretaker lease (and the margin): a split; past the longest handle lease: a handle.
        chain.now = pred + chain.caretakerLease + 86_400 + 2 * 3_600 + 600
        c.sync()
        c.setCaretaker(mapOf(1L to 100L))
        chain.now = pred + chain.handleLease + 86_400 + 2 * 3_600 + 600
        c.sync()
        c.bindHandle("carol")
        assertNotNull(entry(chain, "carol"))
        // The chain refuses a statement past its own bound whatever the wallet names.
        dumpWitnesses(chain, "predecessorBounds")
    }
}
