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
import network.erth.earth.proto.personhood.Membership
import network.erth.earth.proto.personhood.MsgBindReferrer
import network.erth.earth.proto.personhood.MsgClaimAnml
import network.erth.earth.proto.personhood.MsgRegister
import network.erth.earth.proto.personhood.MsgSetCaretaker
import network.erth.earth.proto.shielded.MsgShield
import network.erth.earth.proto.shielded.MsgTransfer
import network.erth.earth.proto.shielded.Transfer
import network.erth.earth.proto.shieldedstaking.MsgClaimUnbonding
import network.erth.earth.proto.shieldedstaking.MsgDelegate
import network.erth.earth.proto.shieldedstaking.MsgLockPosition
import network.erth.earth.proto.shieldedstaking.MsgPositionVote
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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every private msg built by the wallet, against the chain's gogoproto Marshal
 * of the same msg and the chain's own Signal() of it (tools/privacyvectors).
 */
class PrivateMsgsTest {
    private val chainId = "earth-1"
    private fun fb(i: Long): ByteString = ByteString.copyFrom(fe(i).toBytes())
    private fun bs(s: String): ByteString = ByteString.copyFromUtf8(s)
    private fun addr(b: Int): String = Bech32.encode("earth", Bech32.convertBits(ByteArray(20) { (b + it).toByte() }, 8, 5, true))

    private fun transfer(seed: Long, fee: Long, valueOut: Long, denomOut: String): Transfer = Transfer.newBuilder()
        .setProof(ByteString.copyFrom(byteArrayOf(0xde.toByte(), 0xad.toByte(), seed.toByte())))
        .setRoot(fb(seed))
        .addAllNullifiers(listOf(fb(seed + 1), fb(seed + 2), fb(seed + 3)))
        .addAllCommitments(listOf(fb(seed + 4), fb(seed + 5), fb(seed + 6)))
        .addAllCiphertexts(listOf(bs("ct-$seed-0"), bs("ct-$seed-1"), ByteString.EMPTY))
        .setFee(fee).setValueOut(valueOut).setDenomOut(denomOut)
        .build()

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
    private val sig = ByteString.copyFrom(ByteArray(64) { it.toByte() })

    private fun register(affiliate: String) = MsgRegister.newBuilder()
        .setFee(transfer(40, 2000, 0, "")).setProof(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
        .addAllPublicSignals(listOf("250930", "12345", "678", "9")).setSignatureAlgorithm("lean_poa")
        .setDscDer(ByteString.copyFrom(byteArrayOf(0x30, 0x03, 1, 2, 3))).setIdc(fb(41)).setPcAnml(fb(42))
        .setCiphertextAnml(bs("anml")).setPcErth(fb(43)).setCiphertextErth(bs("erth")).setAffiliate(affiliate)
        .build()

    private val msgs: Map<String, MessageLite> by lazy {
        mapOf(
            "transfer_send" to MsgTransfer.newBuilder().setTransfer(transfer(10, 1500, 0, "")).build(),
            "transfer_unshield" to MsgTransfer.newBuilder().setTransfer(transfer(20, 0, 5000, "uerth"))
                .setReceiver(addr(1)).setFeeFromOutput(1000).build(),
            "shield" to MsgShield.newBuilder().setSender(addr(30))
                .setAmount(Coin.newBuilder().setDenom("uerth").setAmount("100000")).setPc(fb(31)).setCiphertext(bs("gas")).build(),
            "register" to register(addr(50)),
            "register_no_affiliate" to register(""),
            "claim_anml" to MsgClaimAnml.newBuilder().setFee(transfer(50, 2000, 0, "")).setMembership(membership(50))
                .setDay(20360).setPc(fb(51)).setCiphertext(bs("claim")).build(),
            "set_caretaker" to MsgSetCaretaker.newBuilder().setFee(transfer(60, 2000, 0, "")).setMembership(membership(60))
                .addAllPercentages(listOf(w(1, 60), w(7, 40))).setMaxActivation(1_780_000_000).build(),
            "bind_referrer" to MsgBindReferrer.newBuilder().setFee(transfer(70, 2000, 0, "")).setMembership(membership(70))
                .setAddress(addr(50)).setMaxActivation(1_780_000_000).build(),
            "bind_referrer_clear" to MsgBindReferrer.newBuilder().setFee(transfer(71, 2000, 0, "")).setMembership(membership(71))
                .setMaxActivation(1_780_000_000).build(),
            "vote_proposal" to MsgVoteProposal.newBuilder().setFee(transfer(80, 2000, 0, "")).setMembership(membership(80))
                .setProposalId(5).setOption(VoteOption.VOTE_OPTION_YES).build(),
            "propose_removal" to MsgProposeRemoval.newBuilder().setFee(transfer(81, 2000, 0, "")).setMembership(membership(81))
                .setOptionId(3).build(),
            "vote_removal" to MsgVoteRemoval.newBuilder().setFee(transfer(82, 2000, 0, "")).setMembership(membership(82))
                .setOptionId(3).setOption(VoteOption.VOTE_OPTION_NO).build(),
            "delegate" to MsgDelegate.newBuilder().setTransfer(transfer(90, 2000, 500000, "uerth")).setValidator(validator)
                .setPc(fb(91)).setCiphertext(bs("d")).build(),
            "undelegate" to MsgUndelegate.newBuilder().setTransfer(transfer(100, 2000, 400000, derth())).setValidator(validator)
                .setPc(fb(101)).setCiphertext(bs("u")).build(),
            "claim_unbonding" to MsgClaimUnbonding.newBuilder().setTransfer(transfer(110, 0, 400000, "unbond/$validator/17"))
                .setValidator(validator).setEpoch(17).setPc(fb(111)).setCiphertext(bs("c")).setFeeFromOutput(2000).build(),
            "stake_vote" to MsgStakeVote.newBuilder().setTransfer(transfer(120, 0, 400000, derth())).setProposalId(5)
                .setValidator(validator).addAllOptions(opts()).setPc(fb(121)).setCiphertext(bs("v"))
                .setFeeTransfer(transfer(130, 2000, 0, "")).build(),
            "lock_position" to MsgLockPosition.newBuilder().setTransfer(transfer(140, 0, 400000, derth())).setValidator(validator)
                .addSplits(w(2, 100)).setPubkey(ByteString.copyFrom(byteArrayOf(2) + fe(141).toBytes())).build(),
            "update_position" to MsgUpdatePosition.newBuilder().setTransfer(transfer(150, 2000, 0, "")).setPositionId(9)
                .addSplits(w(2, 100)).setSignature(sig).build(),
            "unlock_position" to MsgUnlockPosition.newBuilder().setTransfer(transfer(160, 2000, 0, "")).setPositionId(9)
                .setPc(fb(161)).setCiphertext(bs("x")).setSignature(sig).build(),
            "position_vote" to MsgPositionVote.newBuilder().setTransfer(transfer(170, 2000, 0, "")).setPositionId(9)
                .setProposalId(5).addAllOptions(opts()).setSignature(sig).build(),
        )
    }

    @Test
    fun encodingsAndSignalsMatchTheChain() {
        val want = json.getJSONObject("msgs")
        assertEquals(want.length(), msgs.size)
        for ((name, m) in msgs) {
            val v = want.getJSONObject(name)
            assertEquals("$name proto", v.getString("proto"), hex(m.toByteArray()))
            if (name != "shield") {
                assertEquals("$name type", v.getString("type_url"), PrivateMsgs.typeUrl(m))
                assertEquals("$name signal", v.getString("signal"), PrivateMsgs.signal(m, chainId).toHex())
            }
        }
    }

    @Test
    fun bindingAndFieldEncodings() {
        val b = json.getJSONObject("registration_binding")
        assertEquals(b.getString("with_affiliate"), PrivateMsgs.registrationBinding(msgs["register"] as MsgRegister).toHex())
        assertEquals(b.getString("none"), PrivateMsgs.registrationBinding(msgs["register_no_affiliate"] as MsgRegister).toHex())
        assertEquals(json.getString("options_bytes"), hex(PrivateMsgs.optionsBytes(opts())))
        assertEquals(json.getString("splits_bytes"), hex(PrivateMsgs.splitsBytes(listOf(w(1, 60), w(7, 40)))))
        assertEquals(
            json.getString("position_sign_bytes"),
            hex(PrivateMsgs.positionSignBytes(chainId, "vote", 9, 3, PrivateMsgs.positionVotePayload(5, opts()))),
        )
    }

    @Test
    fun unsignedTxMatchesTheChain() {
        val v = json.getJSONObject("unsigned_tx")
        val m = msgs["claim_anml"]!!
        val raw = UnsignedTx.build(m, gasLimit = v.getLong("gas_limit"))
        assertEquals(v.getString("tx_raw"), hex(raw))
    }
}
