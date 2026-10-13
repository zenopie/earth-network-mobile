package network.erth.wallet.privacy

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.earth.proto.allocation.AllocationWeight
import network.erth.earth.proto.shielded.Action
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.ValueBalance
import network.erth.earth.proto.shieldedstaking.MsgCheckIn
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.wallet.privacy.Vectors.fe
import network.erth.wallet.privacy.Vectors.hex
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.Privacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stake vintages (chain v1.3.0) against the chain's own code
 * (tools/privacyvectors/gen-vintages.sh, vintage_vectors.json): denoms in
 * both forms, retirement times, every vintaged msg's encoding and sighash
 * (MsgCheckIn's included), and the public inputs the chain lays out for
 * them. The msgs mirror vintages.go.in field for field.
 */
class VintagesTest {
    private val json: JSONObject by lazy { JSONObject(Vectors.resource("privacy/vintage_vectors.json").decodeToString()) }
    private val chainId = "earth-1"
    private val v: String get() = json.getString("validator")
    private val v2: String get() = json.getString("validator2")
    private val base get() = json.getInt("base_vintage")
    private val cur get() = json.getInt("current_vintage")

    @Test
    fun denomsMatchTheChain() {
        val d = json.getJSONObject("vintage_denoms")
        for (m in d.keys()) assertEquals(m, d.getString(m), Vintages.denom(v, m.toInt(), base))
        // The base vintage is the unsuffixed (legacy) denom; every other month is suffixed.
        assertEquals("derth/$v", Vintages.denom(v, base, base))
        assertEquals("derth/$v/202611", Vintages.denom(v, 202611, base))
        val ctx = Vintages.Context(base, cur)
        assertEquals("derth/$v/$cur", ctx.currentDenom(v))
        assertEquals(base, ctx.monthOf("derth/$v"))
        assertEquals(202611, ctx.monthOf("derth/$v/202611"))
        assertNull(ctx.monthOf("uerth"))

        val p = json.getJSONObject("parse")
        for (denom in p.keys()) {
            val want = p.getJSONObject(denom)
            val got = Vintages.parse(denom)
            assertEquals(denom, want.getBoolean("ok"), got != null)
            if (got != null) {
                assertEquals(denom, want.getString("valoper"), got.validator)
                assertEquals(denom, want.getInt("month"), got.month)
            }
        }
        // parseDerth reads both forms; anything else is refused.
        assertEquals(v, PrivacyWallet.parseDerth("derth/$v"))
        assertEquals(v, PrivacyWallet.parseDerth("derth/$v/202611"))
        assertTrue(runCatching { PrivacyWallet.parseDerth("derth/$v/2026") }.isFailure)

        val ids = json.getJSONObject("asset_ids")
        for (denom in ids.keys()) assertEquals(denom, ids.getString(denom), Privacy.assetId(denom).toHex())
    }

    @Test
    fun retirementMatchesTheChain() {
        val r = json.getJSONObject("retirement")
        for (m in r.keys()) {
            val want = r.getJSONObject(m)
            assertEquals("$m retires", want.getLong("retires_at"), Vintages.retiresAt(m.toInt()))
            assertEquals("$m starts", want.getLong("start"), Vintages.start(m.toInt()))
            assertEquals("$m live", want.getBoolean("live_in_current"), Vintages.live(m.toInt(), cur))
            assertEquals("$m retired", want.getBoolean("retired_in_current"), Vintages.retired(m.toInt(), cur))
        }
        // The exact second: a vintage is live up to 00:00 UTC on the 1st of its month + 13.
        val at = Vintages.retiresAt(202610)
        assertEquals(202710, Vintages.monthOf(at - 1))
        assertEquals(202711, Vintages.monthOf(at))
        assertEquals(202612, Vintages.oldestLive(202712))
        assertEquals(202701, Vintages.addMonths(202612, 1))
        assertEquals(202512, Vintages.addMonths(202601, -1))
        assertEquals(203002, Vintages.monthOf(Vintages.start(203002) + 28 * 86_400 - 1))
        assertTrue(Vintages.valid(200001) && !Vintages.valid(202613) && !Vintages.valid(199912) && !Vintages.valid(0))
    }

    // ---- the msgs, as vintages.go.in lays them out ----

    private fun fb(i: Long): ByteString = ByteString.copyFrom(fe(i).toBytes())
    private val zero32 = ByteString.copyFrom(ByteArray(32))

    private fun fee(seed: Long, amount: Long): Bundle {
        val b = Bundle.newBuilder()
        for (i in 0L until 2L) {
            val x = seed * 10 + i
            val cv = Grumpkin.valueCommit(Privacy.assetId("uerth"), 1000 + x, Privacy.assetId("uanml"), x, fe(x + 500))
            b.addActions(
                Action.newBuilder().setAnchor(fb(x + 1)).setNullifier(fb(x + 2)).setCommitment(fb(x + 3))
                    .setCv(ByteString.copyFrom(cv.toBytes())).setCiphertext(ByteString.copyFromUtf8("ct-$x"))
                    .setProof(ByteString.copyFrom(byteArrayOf(0xde.toByte(), 0xad.toByte(), x.toByte()))),
            )
        }
        b.addBalances(ValueBalance.newBuilder().setDenom("uerth").setAmount(amount))
        return b.setBindingSig(ByteString.copyFrom(ByteArray(96) { seed.toByte() })).build()
    }

    private fun sct(seed: Int): ByteString = ByteString.copyFrom(ByteArray(201) { (seed xor it).toByte() })
    private fun bct(seed: Int): ByteString = ByteString.copyFrom(ByteArray(177) { (seed + it).toByte() })

    private fun stake(seed: Long, spends: Int, credits: Boolean): StakeProof {
        val p = StakeProof.newBuilder().setProof(ByteString.copyFrom(byteArrayOf(0x5e, seed.toByte())))
            .setAnchor(fb(seed))
            .setCommitment(zero32).setCreditNullifier(zero32).setCreditCommitment(zero32).setDebtRoot(zero32)
            .addAllGroundworksTags(listOf(fb(seed + 8), fb(seed + 9))).setCreditGroundworksTag(zero32)
            .setVoteTag(zero32).setCreditVoteTag(zero32).setPendingKey(zero32)
        for (i in 0 until 2) p.addNullifiers(if (i < spends) fb(seed + 1 + i) else zero32)
        p.setCommitment(fb(seed + 3)).setCiphertext(sct(seed.toInt()))
        if (credits) {
            p.setCreditNullifier(fb(seed + 4)).setCreditCommitment(fb(seed + 5)).setCreditCiphertext(sct(seed.toInt() + 1))
                .setCreditGroundworksTag(fb(seed + 10))
        }
        return p.setClearBefore(1_790_000_000 + seed).setDebtRoot(fb(seed + 6)).build()
    }

    private fun voting(p: StakeProof, seed: Long, w: Long, cw: Long): StakeProof {
        val b = p.toBuilder().setVoteTag(fb(seed + 11)).setVoteWeight(w)
        if (cw > 0) b.setCreditVoteTag(fb(seed + 12)).setCreditVoteWeight(cw)
        return b.build()
    }

    private fun pending(p: StakeProof, seed: Long, time: Long, ex: Long): StakeProof =
        p.toBuilder().setPendingKey(fb(seed + 13)).setPendingTime(time).setPendingExposed(ex).build()

    private val splits = listOf(AllocationWeight.newBuilder().setOptionId(2).setPercent(100).build())

    private val checkIn: MsgCheckIn get() = MsgCheckIn.newBuilder().setBundle(fee(300, 2000)).setValidator(v).setFromVintage(202611).setAmount(400_000)
        .setStake(stake(300, 1, credits = true)).build()

    private val msgs: Map<String, MessageLite> get() = mapOf(
        "check_in" to checkIn,
        "check_in_vote" to MsgCheckIn.newBuilder().setBundle(fee(310, 2000)).setValidator(v).setFromVintage(base).setAmount(400_000)
            .setStake(pending(voting(stake(310, 1, credits = true), 310, 0, 400_000), 310, 1_790_000_100, 300_000)).addAllGroundworksSplit(splits).build(),
        "delegate_vintage" to MsgDelegate.newBuilder().setBundle(fee(320, 502000)).setValidator(v).setAmount(500000).setDerth(449_995)
            .setStake(stake(320, 1, credits = false)).setVintage(cur).build(),
        "restake_vintage" to MsgRestake.newBuilder().setBundle(fee(330, 2000)).setValidator(v).setStake(stake(330, 2, credits = false)).setVintage(202611).build(),
        "undelegate_withdrawal" to MsgUndelegate.newBuilder().setBundle(fee(340, 2000)).setValidator(v).setAmount(400000)
            .setStake(stake(340, 1, credits = false)).setPc(fb(341)).setCiphertext(bct(34)).setVintage(base).build(),
        "redelegate_vintage" to MsgRedelegate.newBuilder().setBundle(fee(350, 2000)).setSrcValidator(v).setDstValidator(v2).setAmount(400000)
            .setStake(stake(350, 1, credits = true)).setDstDerth(380_000).setMoveTime(1_790_000_123).setVintage(202611).build(),
        "stake_vote_vintage" to MsgStakeVote.newBuilder().setBundle(fee(360, 2000)).setProposalId(7).setValidator(v)
            .addOptions(WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("1.000000000000000000"))
            .setWeight(999).setProof(ByteString.copyFrom(byteArrayOf(0x70, 0x7e))).addAllVoteNullifiers(listOf(fb(361), fb(362))).setDebtRoot(fb(363))
            .setVintage(202611).build(),
    )

    /** Every vintaged msg (MsgCheckIn too): the chain's encoding, type URL, sighash and fee. */
    @Test
    fun vintagedMsgsMatchTheChain() {
        val want = json.getJSONObject("msgs")
        assertEquals(want.length(), msgs.size)
        for ((name, m) in msgs) {
            val w = want.getJSONObject(name)
            assertEquals("$name proto", w.getString("proto"), hex(m.toByteArray()))
            assertEquals("$name type", w.getString("type_url"), PrivateMsgs.typeUrl(m))
            assertEquals("$name sighash", w.getString("sighash"), PrivateMsgs.sighash(m, chainId, PrivateMsgs.TxFields(gasLimit = w.getLong("gas_limit"))).toHex())
            assertEquals("$name fee", w.getString("private_fee").toLong(), PrivateMsgs.privateFee(m))
        }
    }

    /**
     * MsgCheckIn's shape: a private msg (no signer) whose bundle pays the fee
     * only, a stake proof spending in lane A and crediting in lane B, and a
     * sighash binding validator, from_vintage, amount and the split after
     * the StakeFields (nothing appended: from_vintage is always named).
     */
    @Test
    fun checkInShapeAndSighash() {
        val m = checkIn
        assertEquals(PrivateMsgs.CHECK_IN, PrivateMsgs.typeUrl(m))
        assertEquals("/earth.shieldedstaking.v1.MsgCheckIn", PrivateMsgs.CHECK_IN)
        assertEquals(listOf(m.bundle), PrivateMsgs.bundles(m))
        assertEquals(m.stake, PrivateMsgs.stake(m))
        assertEquals(2000L, PrivateMsgs.privateFee(m))
        val fields = PrivateMsgs.sighashFields(m)
        assertEquals(20 + 4, fields.size)
        assertEquals(PrivateMsgs.stakeFields(m.stake), fields.take(20))
        assertEquals(Privacy.bytes(v.toByteArray()), fields[20])
        assertEquals(Privacy.u64(202611), fields[21])
        assertEquals(Privacy.u64(400_000), fields[22])
        assertEquals(Privacy.bytes(ByteArray(0)), fields[23])
        // Another month or amount is another sighash.
        val s0 = PrivateMsgs.sighash(m, chainId, PrivateMsgs.TxFields())
        assertFalse(s0 == PrivateMsgs.sighash(m.toBuilder().setFromVintage(202612).build(), chainId, PrivateMsgs.TxFields()))
        assertFalse(s0 == PrivateMsgs.sighash(m.toBuilder().setAmount(399_999).build(), chainId, PrivateMsgs.TxFields()))
    }

    /** vintage 0 hashes as before v1.3.0; a named month (the base's too) is appended, so the two spellings never share a sighash. */
    @Test
    fun vintageZeroKeepsTheOldSighash() {
        val d = msgs.getValue("delegate_vintage") as MsgDelegate
        val old = d.toBuilder().setVintage(0).build()
        val named = d.toBuilder().setVintage(base).build()
        assertEquals(PrivateMsgs.sighashFields(old) + Privacy.u64(base.toLong()), PrivateMsgs.sighashFields(named))
        assertFalse(PrivateMsgs.sighash(old, chainId, PrivateMsgs.TxFields()) == PrivateMsgs.sighash(named, chainId, PrivateMsgs.TxFields()))
        // A pre-v1.3.0 msg's bytes are unchanged (proto3 leaves the zero field out).
        assertEquals(hex(old.toByteArray()), hex(d.toBuilder().clearVintage().build().toByteArray()))
    }

    /** The stake and vote circuits' public inputs with vintaged assets: lane A's month, the credit lane's current month, the vote's asset. */
    @Test
    fun publicInputsMatchTheChain() {
        val want = json.getJSONObject("public_inputs")
        val laid = mapOf(
            "check_in" to checkIn,
            "check_in_base" to msgs.getValue("check_in_vote"),
            "delegate_vintage" to msgs.getValue("delegate_vintage"),
            "undelegate_base_0" to (msgs.getValue("undelegate_withdrawal") as MsgUndelegate).toBuilder().setVintage(0).build(),
            "redelegate_vintage" to msgs.getValue("redelegate_vintage"),
        )
        for ((name, m) in laid) {
            val got = ChainLayout.stakePublicInputs(PrivateMsgs.stake(m)!!, ChainLayout.lanes(m, base, cur), fe(77))
            val w = want.getJSONArray(name)
            assertEquals("$name count", w.length(), got.size)
            for (i in got.indices) assertEquals("$name input $i", w.getString(i), got[i].toHex())
        }
        val vote = msgs.getValue("stake_vote_vintage") as MsgStakeVote
        val got = ChainLayout.votePublicInputs(vote, Fr.fromBytes(fb(131).toByteArray()), Fr.fromBytes(fb(132).toByteArray()), fe(77), base)
        val w = want.getJSONArray("stake_vote_vintage")
        for (i in got.indices) assertEquals("vote input $i", w.getString(i), got[i].toHex())
        // The check-in's lanes: the old asset leaving, the current one credited, unlabelled.
        val l = ChainLayout.lanes(checkIn, base, cur)
        assertEquals("derth/$v/202611", l.denom)
        assertEquals("derth/$v/$cur", l.crDenom)
        assertEquals(400_000L, l.vOut)
        assertEquals(400_000L, l.crVIn)
        assertEquals(0L, l.crMoveTime)
    }

    /** The check-in reminder: from 30 days before the oldest live vintage holding stake retires, until it does; never without stake. */
    @Test
    fun checkInReminderDueDates() {
        val retires = Vintages.retiresAt(202610)
        val q = Reminders.Inputs(retires, identityLive = false, claimOpensAt = null, claimedToday = true, caretakerExpiresAt = 0, handle = "", handleEntry = null)
        fun due(now: Long, at: Long = retires) = Reminders.due(q.copy(now = now, stakeRetiresAt = at))
        val reminder = listOf<Reminders.Reminder>(Reminders.Reminder.CheckInDue(retires))
        assertTrue(due(retires - Vintages.CHECK_IN_LEAD_SECONDS - 1).isEmpty())
        assertEquals(reminder, due(retires - Vintages.CHECK_IN_LEAD_SECONDS))
        assertEquals(reminder, due(retires - 1))
        // Retired: nothing to check in any more (the row offers Withdraw).
        assertTrue(due(retires).isEmpty())
        assertTrue(due(retires - 86_400, at = 0).isEmpty())
        // Not tied to a live identity (stake is not).
        assertEquals(reminder, Reminders.due(q.copy(now = retires - 86_400, identityLive = false, stakeRetiresAt = retires)))
        assertTrue(Reminders.text(Reminders.Reminder.CheckInDue(retires), retires - 7 * 86_400).contains("within 7 days"))
        // Hostile times saturate.
        Reminders.due(q.copy(now = Long.MIN_VALUE, stakeRetiresAt = Long.MAX_VALUE))
        Reminders.text(Reminders.Reminder.CheckInDue(Long.MAX_VALUE), Long.MIN_VALUE)
    }
}
