package network.erth.wallet.chain

import org.junit.Assert.assertEquals
import org.junit.Test

/** MsgAddAddressOption's encoding, pinned to the chain's own Marshal of the same message. */
class AddAddressOptionTest {

    @Test
    fun encodesAsTheChainDoes() {
        val msg = Allocation.addCaretakerOption("earth1submitter", "Clean water · Lagos", "earth1recipient")
        val hex = msg.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(
            "0a0f6561727468317375626d697474657210011a14436c65616e20776174657220c2b7204c61676f73220f656172746831726563697069656e74",
            hex,
        )
    }

    @Test
    fun leavesTheClaimerEmpty() {
        assertEquals("", Allocation.addCaretakerOption("a", "b", "c").claimer)
    }
}
