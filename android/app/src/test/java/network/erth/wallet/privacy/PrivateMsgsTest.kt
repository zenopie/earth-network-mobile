package network.erth.wallet.privacy

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import cosmos.base.v1beta1.CoinOuterClass.Coin
import cosmos.gov.v1.VoteOption as GovVoteOption
import cosmos.gov.v1.WeightedVoteOption
import network.erth.earth.proto.allocation.AllocationWeight
import network.erth.earth.proto.assembly.MsgProposeRemoval
import network.erth.earth.proto.assembly.MsgVoteProposal
import network.erth.earth.proto.assembly.MsgVoteRemoval
import network.erth.earth.proto.assembly.VoteOption
import network.erth.earth.proto.dex.MsgAddLiquidityShielded
import network.erth.earth.proto.dex.MsgBuyAnml
import network.erth.earth.proto.dex.MsgNoteSwap
import network.erth.earth.proto.dex.MsgRemoveLiquidity
import network.erth.earth.proto.dex.MsgRemoveLiquidityShielded
import network.erth.earth.proto.personhood.Membership
import network.erth.earth.proto.personhood.MsgBindHandle
import network.erth.earth.proto.personhood.MsgMoveCaretaker
import network.erth.earth.proto.personhood.MsgMoveHandle
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.MsgShield
import network.erth.earth.proto.shielded.Action
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shielded.ValueBalance
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
import network.erth.earth.proto.shieldedstaking.MsgRedelegate
import network.erth.earth.proto.shieldedstaking.MsgRestake
import network.erth.earth.proto.shieldedstaking.StakeProof
import network.erth.earth.proto.shieldedstaking.MsgStakeVote
import network.erth.earth.proto.shieldedstaking.MsgUndelegate
import network.erth.earth.proto.shieldedstaking.MsgUnlockPosition
import network.erth.earth.proto.shieldedstaking.MsgUpdatePosition
import network.erth.wallet.crypto.Bech32
import network.erth.wallet.privacy.Vectors.fe
import network.erth.wallet.privacy.Vectors.hex
import network.erth.wallet.privacy.Vectors.json
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.tx.UnsignedTx
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.Privacy
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every private msg built by the wallet, against the chain's gogoproto Marshal
 * of the same msg and the chain's own Sighash of it (tools/privacyvectors).
 */
class PrivateMsgsTest {
    private val chainId = "earth-1"
    private fun fb(i: Long): ByteString = ByteString.copyFrom(fe(i).toBytes())
    private fun bs(s: String): ByteString = ByteString.copyFromUtf8(s)
    private fun addr(b: Int): String = Bech32.encode("earth", Bech32.convertBits(ByteArray(20) { (b + it).toByte() }, 8, 5, true))

    private fun bundle(seed: Long, vararg bal: Pair<String, Long>): Bundle {
        val b = Bundle.newBuilder()
        for (i in 0L until 2L) {
            val x = seed * 10 + i
            val cv = Grumpkin.valueCommit(Privacy.assetId("uerth"), 1000 + x, Privacy.assetId("uanml"), x, fe(x + 500))
            b.addActions(
                Action.newBuilder().setAnchor(fb(x + 1)).setNullifier(fb(x + 2)).setCommitment(fb(x + 3))
                    .setCv(ByteString.copyFrom(cv.toBytes())).setCiphertext(bs("ct-$x"))
                    .setProof(ByteString.copyFrom(byteArrayOf(0xde.toByte(), 0xad.toByte(), x.toByte()))),
            )
        }
        bal.forEach { (d, v) -> b.addBalances(ValueBalance.newBuilder().setDenom(d).setAmount(v)) }
        return b.setBindingSig(ByteString.copyFrom(ByteArray(96) { seed.toByte() })).build()
    }

    private fun fee(seed: Long, amount: Long) = bundle(seed, "uerth" to amount)

    private val zero32 = ByteString.copyFrom(ByteArray(32))

    /** A deterministic 177-byte stand-in for an amount-blind ciphertext (main.go bct). */
    private fun bct(seed: Int): ByteString = ByteString.copyFrom(ByteArray(177) { (seed + it).toByte() })

    /** A deterministic 201-byte stand-in for a wallet stake ciphertext (main.go sct). */
    private fun sct(seed: Int): ByteString = ByteString.copyFrom(ByteArray(201) { (seed xor it).toByte() })

    /** main.go stakeProof: lane A nullifiers, its output, the credit lane, a clear_before and debt root. */
    private fun stake(seed: Long, spends: Int, creates: Boolean, credits: Boolean = false, clears: Boolean = true): StakeProof {
        val p = StakeProof.newBuilder().setProof(ByteString.copyFrom(byteArrayOf(0x5e, seed.toByte())))
            .setAnchor(fb(seed)).setOwnerTag(fb(seed + 8))
            .setCommitment(zero32).setCreditNullifier(zero32).setCreditCommitment(zero32).setDebtRoot(zero32)
        for (i in 0 until 2) p.addNullifiers(if (i < spends) fb(seed + 1 + i) else zero32)
        if (creates) p.setCommitment(fb(seed + 3)).setCiphertext(sct(seed.toInt()))
        if (credits) p.setCreditNullifier(fb(seed + 4)).setCreditCommitment(fb(seed + 5)).setCreditCiphertext(sct(seed.toInt() + 1))
        if (clears) p.setClearBefore(1_790_000_000 + seed).setDebtRoot(fb(seed + 6))
        return p.build()
    }

    private fun membership(seed: Long): Membership = Membership.newBuilder()
        .setProof(ByteString.copyFrom(byteArrayOf(0xbe.toByte(), 0xef.toByte(), seed.toByte())))
        .setRoot(fb(seed + 100)).setNullifier(fb(seed + 101)).build()

    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private fun derth() = "derth/$validator"
    private fun opts() = listOf(
        WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("0.700000000000000000").build(),
        WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_NO).setWeight("0.300000000000000000").build(),
    )
    private fun w(o: Long, p: Long) = AllocationWeight.newBuilder().setOptionId(o).setPercent(p).build()

    private fun register(handle: String) = MsgRegister.newBuilder()
        .setFee(fee(40, 2000)).setProof(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        .addAllPublicSignals(listOf("250930", "12345", "678", "9")).setSignatureAlgorithm("lean_poa")
        .setDscDer(ByteString.copyFrom(byteArrayOf(0x30, 0x03, 1, 2, 3))).setIdc(fb(41)).setPcAnml(fb(42))
        .setCiphertextAnml(bct(42)).setPcErth(fb(43)).setCiphertextErth(bct(43))
        .setAffiliateHandle(handle)
        .build()

    /** The handle vectors' shielded address (owner_pk OwnerPK(7), ek_pub of ek 01..20). */
    private val zaddr: String by lazy { json.getString("handle_address") }

    private val msgs: Map<String, MessageLite> by lazy {
        mapOf(
            "send" to MsgSend.newBuilder().setBundle(fee(10, 1500)).setFee(1500).build(),
            "send_tx_fields" to MsgSend.newBuilder().setBundle(fee(11, 1500)).setFee(1500).build(),
            "send_no_gas" to MsgSend.newBuilder().setBundle(fee(12, 1500)).setFee(1500).build(),
            "unshield" to MsgSend.newBuilder().setBundle(bundle(20, "uanml" to 5000, "uerth" to 2000))
                .setReceiver(addr(1)).setFee(2000).build(),
            "shield" to MsgShield.newBuilder().setSender(addr(30))
                .setAmount(Coin.newBuilder().setDenom("uerth").setAmount("100000")).setPc(fb(31)).setCiphertext(bct(31)).build(),
            "register" to register("alice-01"),
            "register_no_affiliate" to register(""),
            "claim_anml" to MsgClaimAnml.newBuilder().setFee(fee(50, 2000)).setMembership(membership(50))
                .setDay(20360).setPc(fb(51)).setCiphertext(bct(51)).build(),
            "set_caretaker" to MsgSetCaretaker.newBuilder().setFee(fee(60, 2000)).setMembership(membership(60))
                .addAllPercentages(listOf(w(1, 60), w(7, 40))).setMaxPredecessor(1_750_000_000).build(),
            "set_caretaker_no_bound" to MsgSetCaretaker.newBuilder().setFee(fee(61, 2000)).setMembership(membership(61))
                .addAllPercentages(listOf(w(2, 100))).setMaxPredecessor(Privacy.NO_BOUND).build(),
            "move_caretaker" to MsgMoveCaretaker.newBuilder().setFee(fee(62, 2000)).setMembership(membership(62)).setNewOwner(fb(63)).build(),
            "bind_handle" to MsgBindHandle.newBuilder().setFee(fee(70, 2000)).setMembership(membership(70))
                .setHandle("alice-01").setAddress(zaddr).setMaxPredecessor(1_750_000_000).build(),
            "bind_handle_release" to MsgBindHandle.newBuilder().setFee(fee(71, 2000)).setMembership(membership(71))
                .setMaxPredecessor(Privacy.NO_BOUND).build(),
            "move_handle" to MsgMoveHandle.newBuilder().setFee(fee(72, 2000)).setMembership(membership(72))
                .setHandle("alice-01").setNewOwner(fb(73)).build(),
            "vote_proposal" to MsgVoteProposal.newBuilder().setFee(fee(80, 2000)).setMembership(membership(80))
                .setProposalId(5).setOption(VoteOption.VOTE_OPTION_YES).build(),
            "propose_removal" to MsgProposeRemoval.newBuilder().setFee(fee(81, 2000)).setMembership(membership(81))
                .setOptionId(3).build(),
            "vote_removal" to MsgVoteRemoval.newBuilder().setFee(fee(82, 2000)).setMembership(membership(82))
                .setOptionId(3).setOption(VoteOption.VOTE_OPTION_NO).build(),
            "delegate" to MsgDelegate.newBuilder().setBundle(fee(90, 502000)).setValidator(validator).setAmount(500000).setDerth(449_995)
                .setStake(stake(90, 1, true)).build(),
            "delegate_young" to MsgDelegate.newBuilder().setBundle(fee(91, 502000)).setValidator(validator).setAmount(500000).setDerth(449_995)
                .setStake(stake(91, 1, true, clears = false)).build(),
            "restake" to MsgRestake.newBuilder().setBundle(fee(95, 2000)).setValidator(validator)
                .setStake(stake(95, 2, true)).build(),
            "undelegate" to MsgUndelegate.newBuilder().setBundle(fee(100, 2000)).setValidator(validator).setAmount(400000)
                .setStake(stake(100, 1, true)).setPc(fb(101)).setCiphertext(bct(101)).build(),
            "undelegate_whole" to MsgUndelegate.newBuilder().setBundle(fee(102, 2000)).setValidator(validator).setAmount(400000)
                .setStake(stake(102, 2, true)).setPc(fb(103)).setCiphertext(bct(103)).build(),
            "stake_vote" to MsgStakeVote.newBuilder().setBundle(fee(120, 2000)).setProposalId(5).setValidator(validator)
                .addAllOptions(opts()).setWeight(400000).setProof(ByteString.copyFrom(byteArrayOf(0x70, 0x7e)))
                .addAllVoteNullifiers(listOf(fb(121), fb(122))).setDebtRoot(ByteString.copyFrom(network.erth.wallet.privacy.zk.DebtTree.EMPTY_ROOT.toBytes())).build(),
            "stake_vote_two" to MsgStakeVote.newBuilder().setBundle(fee(127, 2000)).setProposalId(6).setValidator(validator)
                .addAllOptions(opts()).setWeight(999).setProof(ByteString.copyFrom(byteArrayOf(0x70, 0x7e)))
                .addAllVoteNullifiers(listOf(fb(128), fb(129))).setDebtRoot(fb(130)).build(),
            "lock_position" to MsgLockPosition.newBuilder().setBundle(fee(140, 2000)).setValidator(validator).setAmount(400000)
                .addSplits(w(2, 100)).setStake(stake(140, 1, true)).build(),
            "update_position" to MsgUpdatePosition.newBuilder().setBundle(fee(150, 2000)).setPositionId(9)
                .addSplits(w(2, 100)).setStake(stake(150, 0, false)).build(),
            "unlock_position" to MsgUnlockPosition.newBuilder().setBundle(fee(160, 2000)).setPositionId(9)
                .setStake(stake(160, 1, true)).build(),
            "position_vote" to MsgPositionVote.newBuilder().setBundle(fee(170, 2000)).setPositionId(9)
                .setProposalId(5).addAllOptions(opts()).setStake(stake(170, 0, false)).build(),
            "redelegate" to MsgRedelegate.newBuilder().setBundle(fee(175, 2000)).setSrcValidator(validator)
                .setDstValidator(json.getString("validator2")).setAmount(400000).setStake(stake(175, 1, true, credits = true))
                .setDstDerth(380_000).setMoveTime(1_790_000_123).build(),
            "note_swap" to MsgNoteSwap.newBuilder().setBundle(bundle(180, "uanml" to 300000, "uerth" to 2000))
                .setDenomIn("uanml").setAmountIn(300000).setDenomOut("uerth")
                .setMinAmountOut(123456).setPc(fb(181)).setCiphertext(bct(181)).build(),
            "note_swap_to_anml" to MsgNoteSwap.newBuilder().setBundle(fee(200, 302000)).setDenomIn("uerth").setAmountIn(300000)
                .setDenomOut("uanml").setMinAmountOut(1).setPc(fb(201)).setCiphertext(bct(201)).build(),
            "add_liquidity_shielded" to MsgAddLiquidityShielded.newBuilder().setBundle(bundle(210, "uanml" to 700000, "uerth" to 902500))
                .setPoolId(1).setMinShares("777").setRefundPc(fb(211)).setRefundCiphertext(bct(211)).setErthAmount(900000)
                .setSharePc(fb(212)).setShareCiphertext(bct(212)).build(),
            "add_liquidity_shielded_no_min" to MsgAddLiquidityShielded.newBuilder().setBundle(bundle(230, "uanml" to 700000, "uerth" to 902500))
                .setPoolId(1).setRefundPc(fb(231)).setRefundCiphertext(bct(231)).setErthAmount(900000).setSharePc(fb(232))
                .setShareCiphertext(bct(232)).build(),
            "remove_liquidity_shielded" to MsgRemoveLiquidityShielded.newBuilder().setBundle(bundle(240, "dexlp/1" to 4242, "uerth" to 2000))
                .setPoolId(1).setErthPc(fb(241)).setErthCiphertext(bct(241)).setTokenPc(fb(242)).setTokenCiphertext(bct(242)).build(),
            "remove_liquidity_pc" to MsgRemoveLiquidity.newBuilder().setCreator(addr(30)).setPoolId(1)
                .setShares(Coin.newBuilder().setDenom("dexlp/1").setAmount("4242")).setPc(fb(250)).setCiphertext(bct(250)).build(),
            "buy_anml" to MsgBuyAnml.newBuilder().setCreator(addr(30))
                .setTokenIn(Coin.newBuilder().setDenom("uerth").setAmount("5000000")).setMinAmountOut("99").setPc(fb(260))
                .setCiphertext(bct(4)).build(),
        )
    }

    @Test
    fun encodingsAndSighashesMatchTheChain() {
        val want = json.getJSONObject("msgs")
        assertEquals(want.length(), msgs.size)
        for ((name, m) in msgs) {
            val v = want.getJSONObject(name)
            assertEquals("$name proto", v.getString("proto"), hex(m.toByteArray()))
            if (v.has("sighash")) {
                assertEquals("$name type", v.getString("type_url"), PrivateMsgs.typeUrl(m))
                val tx = PrivateMsgs.TxFields(v.getString("memo"), v.getLong("timeout_height"), v.getLong("gas_limit"))
                assertEquals("$name sighash", v.getString("sighash"), PrivateMsgs.sighash(m, chainId, tx).toHex())
                assertEquals("$name total fee", v.getString("total_fee").toLong(), PrivateMsgs.totalFee(m))
                assertEquals("$name private fee", v.getString("private_fee").toLong(), PrivateMsgs.privateFee(m))
            }
        }
    }

    @Test
    fun stakeFieldsMatchTheChain() {
        val want = json.getJSONArray("stake_fields_redelegate")
        val got = PrivateMsgs.stakeFields(stake(175, 1, true, credits = true))
        assertEquals(want.length(), got.size)
        for (i in got.indices) assertEquals("field $i", want.getString(i), got[i].toHex())
    }

    /** The stake and vote circuits' public inputs, as the chain lays them out per msg (FakeChain checks every witness against this layout). */
    @Test
    fun publicInputLayoutsMatchTheChain() {
        val want = json.getJSONObject("public_inputs")
        for (name in listOf("delegate", "undelegate", "redelegate")) {
            val m = msgs.getValue(name)
            val got = ChainLayout.stakePublicInputs(PrivateMsgs.stake(m)!!, ChainLayout.lanes(m), fe(77))
            val w = want.getJSONArray(name)
            assertEquals("$name count", w.length(), got.size)
            assertEquals(network.erth.wallet.privacy.prove.PrivacyProver.Kind.STAKE.publicInputs, got.size)
            for (i in got.indices) assertEquals("$name input $i", w.getString(i), got[i].toHex())
        }
        val vote = msgs.getValue("stake_vote_two") as MsgStakeVote
        val got = ChainLayout.votePublicInputs(vote, Fr.fromBytes(fb(131).toByteArray()), Fr.fromBytes(fb(132).toByteArray()), fe(77))
        val w = want.getJSONArray("stake_vote")
        assertEquals(w.length(), got.size)
        assertEquals(network.erth.wallet.privacy.prove.PrivacyProver.Kind.VOTE.publicInputs, got.size)
        for (i in got.indices) assertEquals("vote input $i", w.getString(i), got[i].toHex())
    }

    @Test
    fun bindingAndFieldEncodings() {
        val b = json.getJSONObject("registration_binding")
        assertEquals(b.getString("with_affiliate"), PrivateMsgs.registrationBinding(msgs["register"] as MsgRegister, chainId).toHex())
        assertEquals(b.getString("none"), PrivateMsgs.registrationBinding(msgs["register_no_affiliate"] as MsgRegister, chainId).toHex())
        assertEquals(b.getString("affiliate_field"), Privacy.affiliateField("alice-01").toHex())
        assertEquals(b.getString("affiliate_field"), PrivateMsgs.affiliateField(msgs["register"] as MsgRegister).toHex())
        assertEquals(json.getString("options_bytes"), hex(PrivateMsgs.optionsBytes(opts())))
        assertEquals(json.getString("splits_bytes"), hex(PrivateMsgs.splitsBytes(listOf(w(1, 60), w(7, 40)))))
    }

    @Test
    fun unsignedTxMatchesTheChain() {
        val v = json.getJSONObject("unsigned_tx")
        val m = msgs["claim_anml"]!!
        val raw = UnsignedTx.build(m, gasLimit = v.getLong("gas_limit"), memo = v.getString("memo"), timeoutHeight = v.getLong("timeout_height"))
        assertEquals(v.getString("tx_raw"), hex(raw))
    }

    /** The membership proof's public inputs (4a663d5): max_predecessor after max_activation. */
    @Test
    fun membershipPublicInputsMatchTheChain() {
        val v = json.getJSONObject("membership_public_inputs")
        val ins = v.getJSONArray("inputs")
        fun h(k: String) = Fr.fromHex(v.getString(k))
        val mine = listOf(h("root"), h("scope"), h("nullifier"), h("signal"), h("excluded_dsc"), h("excluded_country"),
            Privacy.u64(v.getLong("max_activation")), Privacy.u64(v.getLong("max_predecessor")))
        assertEquals(8, ins.length())
        assertEquals(Privacy.NO_BOUND, v.getLong("max_activation"))
        for (i in mine.indices) assertEquals("input $i", ins.getString(i), mine[i].toHex())
    }

    /** A bind's address must be canonical; a release binds Bytes(""), 0, Bytes(""). */
    @Test
    fun bindHandleFields() {
        val rel = msgs["bind_handle_release"] as MsgBindHandle
        assertEquals(listOf(Privacy.bytes(ByteArray(0)), Fr.ZERO, Privacy.bytes(ByteArray(0))), PrivateMsgs.bindHandleFields(rel))
        val bind = msgs["bind_handle"] as MsgBindHandle
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PrivateMsgs.bindHandleFields(bind.toBuilder().setAddress(zaddr.uppercase()).build()) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PrivateMsgs.bindHandleFields(bind.toBuilder().setHandle("Alice").build()) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { PrivateMsgs.bindHandleFields(bind.toBuilder().setAddress("").build()) }
    }

    /** The module accounts the chain declares, and canonical vote weights. */
    @Test
    fun wave3Vectors() {
        val mods = json.getJSONObject("module_accounts")
        assertEquals(PrivateMsgs.MODULE_ACCOUNTS.toSet(), mods.keys().asSequence().toSet())
        for (name in PrivateMsgs.MODULE_ACCOUNTS) {
            assertEquals(name, PrivateMsgs.moduleAccountOf(network.erth.wallet.crypto.Bech32.decode(mods.getString(name))))
        }
        val dec = json.getJSONObject("legacy_dec")
        assertEquals(dec.getString("1"), PrivateMsgs.legacyDec("1"))
        assertEquals(dec.getString("0.5"), PrivateMsgs.legacyDec("0.5"))
        assertEquals(listOf("1.000000000000000000"), PrivateMsgs.canonicalOptions(listOf(WeightedVoteOption.newBuilder().setWeight("1").build())).map { it.weight })
        assertEquals(true, PrivateMsgs.isCalendarDate("261001"))
        assertEquals(false, PrivateMsgs.isCalendarDate("250231"))
        assertEquals(true, PrivateMsgs.isCalendarDate("240229"))
        assertEquals(false, PrivateMsgs.isCalendarDate("250229"))
    }
}
