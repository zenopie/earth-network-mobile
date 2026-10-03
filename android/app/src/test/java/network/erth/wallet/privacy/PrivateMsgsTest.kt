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
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.MsgShield
import network.erth.earth.proto.shielded.Action
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.MsgSend
import network.erth.earth.proto.shielded.ValueBalance
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every private msg built by the wallet, against the chain's gogoproto Marshal
 * of the same msg and the chain's own Sighash of it (android/tools/orchardvectors).
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

    private fun stake(seed: Long, spends: Int, creates: Int): StakeProof {
        val p = StakeProof.newBuilder().setProof(ByteString.copyFrom(byteArrayOf(0x5e, seed.toByte())))
            .setAnchor(fb(seed)).setSpcMint(fb(seed + 7)).setOwnerTag(fb(seed + 8))
        for (i in 0 until 2) {
            p.addNullifiers(if (i < spends) fb(seed + 1 + i) else zero32)
            p.addCommitments(if (i < creates) fb(seed + 3 + i) else zero32)
            p.addCiphertexts(if (i < creates) bs("sct-$seed-$i") else ByteString.EMPTY)
        }
        return p.build()
    }

    private fun membership(seed: Long): Membership = Membership.newBuilder()
        .setProof(ByteString.copyFrom(byteArrayOf(0xbe.toByte(), 0xef.toByte(), seed.toByte())))
        .setRoot(fb(seed + 100)).setNullifier(fb(seed + 101)).build()

    private val validator = "earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
    private fun derth() = "derth/$validator"
    private fun opts() = listOf(
        WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_YES).setWeight("0.7").build(),
        WeightedVoteOption.newBuilder().setOption(GovVoteOption.VOTE_OPTION_NO).setWeight("0.300000000000000000").build(),
    )
    private fun w(o: Long, p: Long) = AllocationWeight.newBuilder().setOptionId(o).setPercent(p).build()

    private fun register(affiliate: String) = MsgRegister.newBuilder()
        .setFee(fee(40, 2000)).setProof(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        .addAllPublicSignals(listOf("250930", "12345", "678", "9")).setSignatureAlgorithm("lean_poa")
        .setDscDer(ByteString.copyFrom(byteArrayOf(0x30, 0x03, 1, 2, 3))).setIdc(fb(41)).setPcAnml(fb(42))
        .setCiphertextAnml(bs("anml")).setPcErth(fb(43)).setCiphertextErth(bs("erth")).setAffiliate(affiliate)
        .build()

    private val msgs: Map<String, MessageLite> by lazy {
        mapOf(
            "send" to MsgSend.newBuilder().setBundle(fee(10, 1500)).setFee(1500).build(),
            "unshield" to MsgSend.newBuilder().setBundle(bundle(20, "uanml" to 5000, "uerth" to 2000))
                .setReceiver(addr(1)).setFee(2000).build(),
            "shield" to MsgShield.newBuilder().setSender(addr(30))
                .setAmount(Coin.newBuilder().setDenom("uerth").setAmount("100000")).setPc(fb(31)).setCiphertext(bs("gas")).build(),
            "register" to register(addr(50)),
            "register_no_affiliate" to register(""),
            "claim_anml" to MsgClaimAnml.newBuilder().setFee(fee(50, 2000)).setMembership(membership(50))
                .setDay(20360).setPc(fb(51)).setCiphertext(bs("claim")).build(),
            "set_caretaker" to MsgSetCaretaker.newBuilder().setFee(fee(60, 2000)).setMembership(membership(60))
                .addAllPercentages(listOf(w(1, 60), w(7, 40))).setMaxActivation(1_780_000_000).build(),
            "bind_referrer" to MsgBindReferrer.newBuilder().setFee(fee(70, 2000)).setMembership(membership(70))
                .setAddress(addr(50)).setMaxActivation(1_780_000_000).build(),
            "bind_referrer_clear" to MsgBindReferrer.newBuilder().setFee(fee(71, 2000)).setMembership(membership(71))
                .setMaxActivation(1_780_000_000).build(),
            "vote_proposal" to MsgVoteProposal.newBuilder().setFee(fee(80, 2000)).setMembership(membership(80))
                .setProposalId(5).setOption(VoteOption.VOTE_OPTION_YES).build(),
            "propose_removal" to MsgProposeRemoval.newBuilder().setFee(fee(81, 2000)).setMembership(membership(81))
                .setOptionId(3).build(),
            "vote_removal" to MsgVoteRemoval.newBuilder().setFee(fee(82, 2000)).setMembership(membership(82))
                .setOptionId(3).setOption(VoteOption.VOTE_OPTION_NO).build(),
            "delegate" to MsgDelegate.newBuilder().setBundle(fee(90, 502000)).setValidator(validator).setFee(2000)
                .setStake(stake(90, 0, 0)).build(),
            "restake" to MsgRestake.newBuilder().setBundle(fee(95, 2000)).setValidator(validator).setFee(2000)
                .setStake(stake(95, 2, 1)).build(),
            "undelegate" to MsgUndelegate.newBuilder().setBundle(fee(100, 2000)).setValidator(validator).setAmount(400000)
                .setFee(2000).setStake(stake(100, 1, 1)).build(),
            "claim_unbonding" to MsgClaimUnbonding.newBuilder().setValidator(validator).setEpoch(17).setAmount(400000)
                .setPc(fb(111)).setFeeFromOutput(2000).setStake(stake(110, 2, 0)).build(),
            "claim_unbonding_fee_bundle" to MsgClaimUnbonding.newBuilder().setBundle(fee(115, 2000)).setValidator(validator)
                .setEpoch(17).setAmount(400000).setPc(fb(116)).setCiphertext(bs("c")).setFee(2000).setStake(stake(117, 1, 1)).build(),
            "stake_vote" to MsgStakeVote.newBuilder().setBundle(fee(120, 2000)).setProposalId(5).setValidator(validator)
                .addAllOptions(opts()).setWeight(400000).setFee(2000).setStake(stake(120, 2, 0)).build(),
            "lock_position" to MsgLockPosition.newBuilder().setBundle(fee(140, 2000)).setValidator(validator).setAmount(400000)
                .addSplits(w(2, 100)).setFee(2000).setStake(stake(140, 1, 1)).build(),
            "update_position" to MsgUpdatePosition.newBuilder().setBundle(fee(150, 2000)).setPositionId(9)
                .addSplits(w(2, 100)).setFee(2000).setStake(stake(150, 0, 0)).build(),
            "unlock_position" to MsgUnlockPosition.newBuilder().setBundle(fee(160, 2000)).setPositionId(9)
                .setFee(2000).setStake(stake(160, 0, 0)).build(),
            "position_vote" to MsgPositionVote.newBuilder().setBundle(fee(170, 2000)).setPositionId(9)
                .setProposalId(5).addAllOptions(opts()).setFee(2000).setStake(stake(170, 0, 0)).build(),
            "note_swap" to MsgNoteSwap.newBuilder().setBundle(bundle(180, "uanml" to 300000, "uerth" to 2000)).setDenomOut("uerth")
                .setMinAmountOut(123456).setPc(fb(181)).setFee(2000).build(),
            "note_swap_fee_from_output" to MsgNoteSwap.newBuilder().setBundle(bundle(190, "uanml" to 300000)).setDenomOut("uerth")
                .setMinAmountOut(123456).setPc(fb(191)).setCiphertext(bs("s")).setFeeFromOutput(3000).build(),
            "note_swap_to_anml" to MsgNoteSwap.newBuilder().setBundle(fee(200, 302000)).setDenomOut("uanml")
                .setMinAmountOut(1).setPc(fb(201)).setFee(2000).build(),
            "add_liquidity_shielded" to MsgAddLiquidityShielded.newBuilder().setBundle(bundle(210, "uanml" to 700000, "uerth" to 902500))
                .setPoolId(1).setMinShares("777").setRefundPc(fb(211)).setFee(2500).setSharePc(fb(212)).build(),
            "add_liquidity_shielded_no_min" to MsgAddLiquidityShielded.newBuilder().setBundle(bundle(230, "uanml" to 700000, "uerth" to 902500))
                .setPoolId(1).setRefundPc(fb(231)).setRefundCiphertext(bs("r")).setFee(2500).setSharePc(fb(232))
                .setShareCiphertext(bs("sh")).build(),
            "remove_liquidity_shielded" to MsgRemoveLiquidityShielded.newBuilder().setBundle(bundle(240, "dexlp/1" to 4242, "uerth" to 2000))
                .setPoolId(1).setFee(2000).setErthPc(fb(241)).setTokenPc(fb(242)).setTokenCiphertext(bs("t")).build(),
            "remove_liquidity_pc" to MsgRemoveLiquidity.newBuilder().setCreator(addr(30)).setPoolId(1)
                .setShares(Coin.newBuilder().setDenom("dexlp/1").setAmount("4242")).setPc(fb(250)).build(),
            "buy_anml" to MsgBuyAnml.newBuilder().setCreator(addr(30))
                .setTokenIn(Coin.newBuilder().setDenom("uerth").setAmount("5000000")).setMinAmountOut("99").setPc(fb(260)).build(),
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
                assertEquals("$name sighash", v.getString("sighash"), PrivateMsgs.sighash(m, chainId).toHex())
                assertEquals("$name total fee", v.getString("total_fee").toLong(), PrivateMsgs.totalFee(m))
            }
        }
    }

    @Test
    fun stakeFieldsMatchTheChain() {
        val want = json.getJSONArray("stake_fields_undelegate")
        val got = PrivateMsgs.stakeFields(stake(100, 1, 1))
        assertEquals(want.length(), got.size)
        for (i in got.indices) assertEquals("field $i", want.getString(i), got[i].toHex())
    }

    @Test
    fun bindingAndFieldEncodings() {
        val b = json.getJSONObject("registration_binding")
        assertEquals(b.getString("with_affiliate"), PrivateMsgs.registrationBinding(msgs["register"] as MsgRegister).toHex())
        assertEquals(b.getString("none"), PrivateMsgs.registrationBinding(msgs["register_no_affiliate"] as MsgRegister).toHex())
        assertEquals(json.getString("options_bytes"), hex(PrivateMsgs.optionsBytes(opts())))
        assertEquals(json.getString("splits_bytes"), hex(PrivateMsgs.splitsBytes(listOf(w(1, 60), w(7, 40)))))
    }

    @Test
    fun unsignedTxMatchesTheChain() {
        val v = json.getJSONObject("unsigned_tx")
        val m = msgs["claim_anml"]!!
        val raw = UnsignedTx.build(m, gasLimit = v.getLong("gas_limit"))
        assertEquals(v.getString("tx_raw"), hex(raw))
    }
}
