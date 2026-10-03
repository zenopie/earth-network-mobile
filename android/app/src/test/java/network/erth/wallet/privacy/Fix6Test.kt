package network.erth.wallet.privacy

import network.erth.wallet.chain.ChainErrors
import network.erth.wallet.chain.math.SwapMath
import network.erth.wallet.privacy.handles.HandleEntry
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.sync.HttpPrivacyIndexer
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.sync.WalletSync
import network.erth.wallet.privacy.tx.NoteOut
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Clients round 6 (chain 203d3b2, ORCHARD_DESIGN 16): the open referral note
 * and the notes stream format 2, split LP payouts (one ciphertext at several
 * positions), handles and caretaker splits that are held but not live, lease
 * bounds from Query/LeaseBounds, anchors with margin, the swap fee rounded up,
 * withdrawal note legs and the new chain errors.
 */
class Fix6Test {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val bob = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val carol = "letter advice cage absurd amount doctor acoustic avoid letter advice cage above"

    private fun reads(chain: FakeChain, bounds: () -> PrivacyChainReads.LeaseBounds = chain::leaseBounds) = object : PrivacyChainReads {
        override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(chain.caretakerLease, 3_600, chain.handleLease, chain.handleRenewal)
        override fun leaseBounds() = bounds()
        override fun ballotInputs(proposalId: Long, optionId: Long) =
            PrivacyChainReads.BallotInputs(Privacy.proposalScope(proposalId, 0), Fr.ZERO, Fr.ZERO, Privacy.NO_BOUND, 0, 0, chain.ballotMaxPredecessor())
        override fun epochNumber() = chain.epoch
        override fun snapshot(proposalId: Long) = chain.snapshotRead(proposalId)
        override fun stakeNullifierTree(start: Long, limit: Int) = chain.nfTreeRead(start, limit)
        override fun positions() = chain.positionReads()
    }

    private fun wallet(chain: FakeChain, words: String, bounds: (() -> PrivacyChainReads.LeaseBounds)? = null) = PrivacyWallet(
        PrivacyKeys.fromMnemonic(words), PrivacyStore.memory(), chain, chain, reads(chain, bounds ?: chain::leaseBounds), chain.prover, chain.chainId, chain,
        now = { chain.now },
    )

    private fun bal(w: PrivacyWallet, d: String = "uerth") = w.balances()[d] ?: 0L

    private fun register(chain: FakeChain, w: PrivacyWallet, passport: String) {
        val prep = w.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc, prep.gas.ciphertext)
        w.sync()
        w.register(prep, ByteArray(14_656), listOf("261001", prep.binding.toBigInteger().toString(), passport, Fr.of(77).toBigInteger().toString()), "lean_poa", ByteArray(10))
        w.sync()
        assertEquals(WalletSync.IdentityStatus.LIVE, w.identityStatus())
    }

    /** [w] registers [passport] after another wallet did: a switch, its leaf naming a predecessor. Funded for fees. */
    private fun switched(chain: FakeChain, w: PrivacyWallet, passport: String) {
        register(chain, w, passport)
        w.shieldOutput("uerth", 5_000_000).let { chain.shield("uerth", 5_000_000, it.pc, it.ciphertext) }
        w.sync()
        assertTrue(w.store.state.identity!!.predecessorAt > 0)
    }

    private fun entry(chain: FakeChain, h: String): HandleEntry? = chain.handleDirectory().lookup(h)

    // ---- notes stream format 2 ----------------------------------------------

    private val fields = """["position","height","cm","ciphertext","amount","owner_pk","rho","rcm"]"""
    private val hex0 = "00".repeat(32)
    private val hex1 = "00".repeat(31) + "01"

    @Test
    fun notesFormat2ByNameWithOpenRows() {
        // Columns read by name, in any order; the three row kinds.
        val page = HttpPrivacyIndexer.parseNotes(JSONObject("""{"format":2,"fields":["rcm","rho","owner_pk","amount","ciphertext","cm","height","position"],
            "notes":[[null,null,null,null,"AAAA","$hex1",5,0],[null,null,null,"7uerth","AAAA","$hex1",5,1],["$hex1","$hex0","$hex1","5000000uerth",null,"$hex1",6,2]],
            "next_pos":3,"complete":false,"synced_height":6}"""))
        assertEquals(listOf(0L, 1L, 2L), page.rows.map { it.position })
        assertNull(page.rows[0].amount); assertNull(page.rows[0].ownerPk)
        assertEquals("7uerth", page.rows[1].amount); assertNull(page.rows[1].rho)
        val open = page.rows[2]
        assertEquals(0, open.ciphertext.size)
        assertEquals(Fr.of(1), open.ownerPk); assertEquals(Fr.ZERO, open.rho); assertEquals(Fr.of(1), open.rcm)
        // An old backend (format 1, five columns) is refused, not misread.
        assertThrows(java.io.IOException::class.java) {
            HttpPrivacyIndexer.parseNotes(JSONObject("""{"notes":[[0,1,"$hex1","AAAA",null]],"next_pos":1,"complete":false,"synced_height":1}"""))
        }
        // Part of an opening, an opening beside a ciphertext, an open row with no amount: refused.
        for (row in listOf(
            """[0,1,"$hex1",null,"5uerth","$hex1",null,"$hex1"]""",
            """[0,1,"$hex1","AAAA","5uerth","$hex1","$hex1","$hex1"]""",
            """[0,1,"$hex1",null,null,"$hex1","$hex1","$hex1"]""",
            """[0,1,"$hex1",null,"5uerth",null,null,null]""",
        )) {
            assertThrows(row, java.io.IOException::class.java) {
                HttpPrivacyIndexer.parseNotes(JSONObject("""{"format":2,"fields":$fields,"notes":[$row],"next_pos":1,"complete":false,"synced_height":1}"""))
            }
        }
    }

    @Test
    fun openNoteIsOursOnlyByOwnerPkAndCm() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        val (rho, rcm) = Privacy.referralOpening(Fr.of(4242), 3)
        val pos = chain.mintOpen("uerth", 5_000_000, a.keys.ownerPk, rho, rcm)
        chain.emptyBlock()
        // A row naming alice's owner_pk but a cm of another amount is not a note of hers.
        val cmOther = Privacy.cm(Privacy.assetId("uerth"), 6_000_000, Privacy.pc(a.keys.ownerPk, rho, rcm))
        val lie = chain.noteTree.append(cmOther)
        chain.notes.add(network.erth.wallet.privacy.sync.NoteRow(lie, chain.height, cmOther, ByteArray(0), "5000000uerth", a.keys.ownerPk, rho, rcm))
        chain.emptyBlock()
        a.sync(); b.sync()
        assertEquals(listOf(pos), a.notes.map { it.position })
        assertEquals(5_000_000L, bal(a))
        assertTrue(b.notes.isEmpty())
        // Spendable: alice sends from it.
        a.send(b.address, "uerth", 1_000_000)
        b.sync()
        assertEquals(1_000_000L, bal(b))
        dumpWitnesses(chain, "fix6OpenNote")
    }

    // ---- split LP payouts ---------------------------------------------------

    @Test
    fun splitPayoutOneCiphertextAtSeveralPositions() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        // MintNoteSplit: one pc and one ciphertext, several notes, each its own amount and position
        // (the test's chunks are ones this wallet holds; the chain cuts at 2^64-1).
        val out = a.withdrawalNote()
        val values = listOf(3_000_000L, 3_000_000L, 1_234L)
        val ps = chain.mintSplit("uanml", values, out.pc, out.ciphertext)
        chain.emptyBlock()
        a.sync()
        val got = a.notes.filter { it.position in ps }.sortedBy { it.position }
        assertEquals(values, got.map { it.note.value })
        // One rho and rcm, a nullifier per position: every chunk spends on its own.
        assertEquals(1, got.map { it.note.rho to it.note.rcm }.toSet().size)
        assertEquals(3, got.map { it.nf }.toSet().size)
        got.forEach { assertEquals(Privacy.nf(a.keys.nk, it.note.rho, it.position), it.nf) }
        assertEquals(6_001_234L, bal(a, "uanml"))
        // Two chunks of the same value are still two notes.
        assertEquals(got[0].note, got[1].note)
        assertTrue(got[0] != got[1])
    }

    @Test
    fun withdrawalNoteLegsAreBoundedAtStart() {
        val total = BigInteger.valueOf(1_000)
        val big = PrivacyWallet.MAX_WITHDRAWAL_NOTE_LEG.multiply(BigInteger.valueOf(2))
        // Half the shares of a pool holding twice the cap: exactly the cap, allowed.
        PrivacyWallet.checkWithdrawalNoteLegs(BigInteger.valueOf(500), total, BigInteger.TEN, big, "uanml", erthNote = true, tokenNote = true)
        val e = assertThrows(PrivacyWallet.WithdrawalTooLarge::class.java) {
            PrivacyWallet.checkWithdrawalNoteLegs(BigInteger.valueOf(501), total, BigInteger.TEN, big, "uanml", erthNote = true, tokenNote = true)
        }
        assertTrue(e.message!!.contains("uanml") && e.message!!.contains("smaller parts"))
        // A leg paid to the account is not a note leg.
        PrivacyWallet.checkWithdrawalNoteLegs(BigInteger.valueOf(501), total, big, BigInteger.TEN, "uanml", erthNote = false, tokenNote = true)
        // The wallet's bound is below x/dex's (16 x (2^64-1)) and below one note it can hold.
        assertTrue(PrivacyWallet.MAX_WITHDRAWAL_NOTE_LEG < BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE).multiply(BigInteger.valueOf(16)))
        assertTrue(PrivacyWallet.MAX_WITHDRAWAL_NOTE_LEG <= BigInteger.valueOf(Long.MAX_VALUE))
    }

    // ---- handles and caretaker splits held but not live ------------------------

    @Test
    fun handleInItsRenewalPeriodIsBoundedLikeAClaim() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "501")
        a.bindHandle("alice")
        assertEquals(entry(chain, "alice")!!.expiresAt, a.handleExpiresAt())
        // Lapsed into its renewal period: a fresh identity meets the claim bound and renews.
        chain.now += chain.handleLease + 10
        a.sync()
        assertEquals(HandleEntry.RENEWAL, entry(chain, "alice")!!.status)
        a.bindHandle("alice")
        assertEquals(HandleEntry.LIVE, entry(chain, "alice")!!.status)
        assertTrue(chain.prover.allMemberships.last().maxPredecessor < Privacy.NO_BOUND)

        // A switched identity whose own bound has not passed holds a handle (moved to it), lets it
        // lapse: renewing now is a claim it cannot make. Refused before anything is sent.
        val b = wallet(chain, bob)
        register(chain, b, passport = "502")
        b.bindHandle("bobby")
        b.sync()
        chain.now += 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "502")
        // (the handle moves before the switch in the app; here it is recorded as moved in)
        val nfC = b.newOwner(c.keys, Privacy.handleScope())
        chain.handles["bobby"] = chain.handles.getValue("bobby").copy(nullifier = nfC)
        c.adoptMoved("bobby", null, 0)
        c.reconcileHandle(mapOf("bobby" to entry(chain, "bobby")!!), chain.now + 1)
        assertTrue(c.handleExpiresAt() > chain.now)
        // Live: renewed with no bound (it holds it).
        c.bindHandle("bobby")
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        c.sync()
        chain.now += chain.handleLease + 10
        c.sync()
        c.reconcileHandle(mapOf("bobby" to entry(chain, "bobby")!!), chain.now + 1)
        val sent = chain.txs.size
        val e = runCatching { c.bindHandle("bobby") }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.HandleNotLive && e.message!!.contains("renewal period"))
        assertEquals(sent, chain.txs.size)
        // ... and it cannot be moved either.
        val m = runCatching { c.moveHandle(c.newOwner(PrivacyKeys.fromMnemonic(alice), Privacy.handleScope())) }.exceptionOrNull()
        assertTrue("$m", m is PrivacyWallet.HandleNotMovable)
        assertEquals(sent, chain.txs.size)
        dumpWitnesses(chain, "fix6HandleRenewal")
    }

    @Test
    fun aHandleWhoseExpiryTheWalletLacksIsTriedAndTheChainDecides() {
        val chain = FakeChain()
        val b = wallet(chain, bob)
        register(chain, b, passport = "602")
        b.bindHandle("bobby")
        chain.now += 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "602")
        chain.handles["bobby"] = chain.handles.getValue("bobby").copy(nullifier = b.newOwner(c.keys, Privacy.handleScope()))
        c.adoptMoved("bobby", null, 0)
        assertEquals(0L, c.handleExpiresAt())
        chain.now += chain.handleLease + 10
        c.sync()
        // No expiry known: a no-bound attempt; the chain refuses it in its ante (no fee): NotHeld.
        val e = runCatching { c.bindHandle("bobby") }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.NotHeld)
    }

    @Test
    fun aLapsedCaretakerSplitIsANewSplit() {
        val chain = FakeChain()
        val b = wallet(chain, bob)
        register(chain, b, passport = "702")
        b.setCaretaker(mapOf(1L to 100L))
        b.sync()
        chain.now += 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "702")
        // The split moved to c (as MsgMoveCaretaker would), then lapses unswept.
        val exp = chain.now + 3 * 86_400
        val nfB = Privacy.scopeNullifier(b.keys.idSecret, Privacy.caretakerScope())
        val nfC = b.newOwner(c.keys, Privacy.caretakerScope())
        chain.caretakerVotes[nfC] = chain.caretakerVotes.remove(nfB)!!
        chain.caretakerExpiry.remove(nfB); chain.caretakerExpiry[nfC] = exp
        c.adoptMoved(null, mapOf(1L to 100L), exp)
        // Live: refreshed with no bound.
        c.setCaretaker(mapOf(1L to 100L))
        assertEquals(Privacy.NO_BOUND, chain.prover.allMemberships.last().maxPredecessor)
        c.sync()
        chain.now = c.caretakerExpiresAt() + 10
        c.sync()
        val sent = chain.txs.size
        val e = runCatching { c.setCaretaker(mapOf(1L to 100L)) }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.CaretakerLapsed)
        assertEquals(sent, chain.txs.size)
        // Clearing needs no bound.
        c.setCaretaker(emptyMap())
    }

    // ---- lease bounds ---------------------------------------------------------

    @Test
    fun claimBoundUsesTheLongestLeaseNotParams() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        register(chain, a, passport = "801")
        chain.now += 2 * 86_400
        val c = wallet(chain, carol)
        switched(chain, c, passport = "801")
        val pred = c.store.state.identity!!.predecessorAt
        // Governance cut the lease to 30 days; the chain still bounds claims by the year ever in force.
        chain.handleLeaseMax = chain.handleLease
        chain.handleLease = 30L * 86_400
        chain.now = pred + 30L * 86_400 + 86_400 + 2 * 3_600 + 600
        c.sync()
        val reads0 = chain.leaseBoundsReads
        val e = runCatching { c.bindHandle("carol") }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.NotYet && e.waitSeconds > 300L * 86_400)
        assertTrue(chain.leaseBoundsReads > reads0)
        assertNull(entry(chain, "carol"))
        // Past the longest lease: a claim, bounded by it.
        chain.now = pred + chain.handleLeaseMax + 86_400 + 2 * 3_600 + 600
        c.sync()
        c.bindHandle("carol")
        assertNotNull(entry(chain, "carol"))
        val mp = chain.prover.allMemberships.last().maxPredecessor
        assertTrue(mp < chain.now - chain.handleLeaseMax - 86_400 && mp % 3_600 == 0L && mp >= pred)
        // The caretaker bound likewise: a held longer lease after a cut keeps bounding splits.
        chain.caretakerLeaseHold = 400L * 86_400
        val v = runCatching { c.setCaretaker(mapOf(1L to 100L)) }.exceptionOrNull()
        assertTrue("$v", v is PrivacyWallet.NotYet)
        dumpWitnesses(chain, "fix6LeaseBounds")
    }

    @Test
    fun leaseBoundsThatDoNotAddUpAreRefused() {
        val chain = FakeChain()
        val a = wallet(chain, alice) { chain.leaseBounds().let { it.copy(handleClaimBound = it.handleClaimBound + 1) } }
        register(chain, a, passport = "901")
        val e = runCatching { a.bindHandle("alice") }.exceptionOrNull()
        assertTrue("$e", e is java.io.IOException && e.message!!.contains("do not add up"))
        val z = wallet(chain, bob) { chain.leaseBounds().copy(handleLeaseSeconds = 0) }
        register(chain, z, passport = "902")
        assertTrue(runCatching { z.bindHandle("bob") }.exceptionOrNull() is IllegalArgumentException)
    }

    // ---- anchors --------------------------------------------------------------

    @Test
    fun anAnchorAboutToLapseIsReplacedOrRefused() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val b = wallet(chain, bob)
        val n = NoteOut.mintToSelf(a.keys, "uerth")
        chain.shield("uerth", 3_000_000, n.pc, n.ciphertext)
        a.sync()
        val stale = a.store.noteTree.root()
        // The local root lapses in a minute (CheckTx would refuse it within 120 s).
        chain.rootExpiresAt = { r -> if (r == stale) chain.now + 60 else 0L }
        val sent = chain.txs.size
        val e = runCatching { a.send(b.address, "uerth", 1_000) }.exceptionOrNull()
        assertTrue("$e", e is PrivacyWallet.SyncFirst)
        assertEquals(sent, chain.txs.size)
        // A newer root exists on the chain: the wallet syncs to it and sends.
        chain.shield("uerth", 1, NoteOut.mintToSelf(b.keys, "uerth").pc, NoteOut.mintToSelf(b.keys, "uerth").ciphertext)
        a.send(b.address, "uerth", 1_000)
        assertTrue(a.store.noteTree.root() != stale)
        // With room to spare (> the margin), the root is used as it is.
        chain.rootExpiresAt = { chain.now + PrivacyWallet.ANCHOR_MARGIN + 60 }
        a.sync()
        a.send(b.address, "uerth", 1_000)
    }

    // ---- dex and errors ---------------------------------------------------------

    @Test
    fun swapFeeRoundsUp() {
        val f = BigDecimal("0.300000000000000000")
        assertEquals(BigInteger.ONE, SwapMath.feeOf(BigInteger.ONE, f))
        assertEquals(BigInteger.valueOf(3), SwapMath.feeOf(BigInteger.valueOf(1_000), f))
        assertEquals(BigInteger.valueOf(4), SwapMath.feeOf(BigInteger.valueOf(1_001), f))
        assertEquals(BigInteger.ZERO, SwapMath.feeOf(BigInteger.valueOf(1_000), BigDecimal.ZERO))
        // A swap's output is net of the larger fee.
        val q = SwapMath.hubForToken(BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_001), f)!!
        assertEquals(BigInteger.valueOf(4), q.feeErth)
    }

    @Test
    fun newChainErrorsAreExplained() {
        assertNotNull(ChainErrors.explain(1101, "dex", "invalid amount: the uanml leg (1) is above 2, the most one withdrawal pays as notes; withdraw in smaller parts"))
        // Another 1101 is not mislabelled.
        assertNull(ChainErrors.explain(1101, "dex", "invalid amount: zero"))
        assertNotNull(ChainErrors.explain(5, "bank", "uanml: send transactions are disabled"))
        assertNotNull(ChainErrors.explain("failed to execute message; message index: 0: \"alice\" is not live (renewal): renew it before moving it: invalid private msg"))
        assertNotNull(ChainErrors.explain(1103, "shielded", "01AB expires at 5, within 120s of the last block: pick a newer anchor: root is not a recent note-tree root"))
        assertNull(ChainErrors.explain(1103, "shielded", "root is not a recent note-tree root"))
    }

    @Test
    fun registrationBindsTheHandleOnly() {
        val chain = FakeChain()
        val a = wallet(chain, alice)
        val prep = a.prepareRegistration(PrivacyWallet.Referrer("bobby", wallet(chain, bob).address))
        val msg = a.registerMsg(prep, ByteArray(1), listOf("1", "2", "3", "4"), "lean_poa", ByteArray(1))
        assertEquals("bobby", msg.affiliateHandle)
        assertEquals(Privacy.affiliateField("bobby"), network.erth.wallet.privacy.tx.PrivateMsgs.affiliateField(msg))
        // No referrer: 0, and no handle on the wire.
        val none = a.registerMsg(a.prepareRegistration(null), ByteArray(1), listOf("1", "2", "3", "4"), "lean_poa", ByteArray(1))
        assertEquals("", none.affiliateHandle)
        assertEquals(Fr.ZERO, network.erth.wallet.privacy.tx.PrivateMsgs.affiliateField(none))
        assertTrue(NotePlaintext.fresh("uerth", 1).rho != Fr.ZERO)
    }
}
