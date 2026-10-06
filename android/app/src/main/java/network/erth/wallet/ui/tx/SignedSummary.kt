package network.erth.wallet.ui.tx

import com.google.protobuf.Any as ProtoAny
import cosmos.bank.v1beta1.MsgSend
import cosmos.base.v1beta1.CoinOuterClass
import network.erth.earth.proto.dex.MsgAddLiquidity
import network.erth.earth.proto.dex.MsgRemoveLiquidity
import network.erth.earth.proto.shielded.MsgShield
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.ui.components.formatUerth

/**
 * A signed tx's confirm sheet, read back from the messages that will be
 * signed rather than from what the screen meant to build: the type, the
 * amounts and the recipient on the sheet are the bytes' own, and a message
 * whose sender is not this wallet is refused before any sheet. The
 * caller's [TxConfirmDetails] keeps the human action and what the bytes
 * cannot say (a shield's recipient is an opaque note commitment, so the
 * caller's label for it stays). Mirrors iOS, where every sheet value is
 * captured once at request time and the msgs are built from those values.
 */
internal object SignedSummary {

    class Mismatch(message: String) : IllegalStateException(message)

    fun describe(msgs: List<ProtoAny>, details: TxConfirmDetails, sender: String): TxConfirmDetails {
        if (msgs.isEmpty()) throw Mismatch("nothing to sign")
        if (sender.isEmpty()) throw Mismatch("no wallet is selected")
        var d = details.copy(msgTypeUrl = msgs.joinToString(", ") { it.typeUrl })
        if (msgs.size != 1) return d
        val m = msgs[0]
        fun own(from: String) { if (from != sender) throw Mismatch("the message is signed for $from, not this wallet") }
        when (m.typeUrl) {
            "/cosmos.bank.v1beta1.MsgSend" -> {
                val s = MsgSend.parseFrom(m.value)
                own(s.fromAddress)
                if (s.amountCount != 1) throw Mismatch("a send of ${s.amountCount} coins")
                d = d.copy(amountValue = coin(s.getAmount(0)), recipient = s.toAddress)
            }
            PrivateMsgs.SHIELD -> {
                val s = MsgShield.parseFrom(m.value)
                own(s.sender)
                d = d.copy(amountValue = coin(s.amount))
            }
            "/earth.dex.v1.MsgAddLiquidity" -> {
                val s = MsgAddLiquidity.parseFrom(m.value)
                own(s.creator)
                d = d.copy(amountValue = "${coin(s.amountA)} + ${coin(s.amountB)}")
            }
            "/earth.dex.v1.MsgRemoveLiquidity" -> {
                val s = MsgRemoveLiquidity.parseFrom(m.value)
                own(s.creator)
                d = d.copy(amountValue = formatUerth(amount(s.shares)))
            }
        }
        return d
    }

    private fun amount(c: CoinOuterClass.Coin): Long =
        c.amount.toLongOrNull()?.takeIf { it >= 0 } ?: throw Mismatch("amount ${c.amount.take(40)} is out of range")

    /** "1.5 ERTH": base units on the chain's one six-decimal scale, the denom's symbol. */
    private fun coin(c: CoinOuterClass.Coin): String = "${formatUerth(amount(c))} ${c.denom.removePrefix("u").uppercase()}"
}
