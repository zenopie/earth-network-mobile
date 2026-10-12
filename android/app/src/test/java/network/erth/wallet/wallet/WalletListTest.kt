package network.erth.wallet.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletListTest {

    private fun list(vararg names: String) = JSONArray().apply { names.forEach { put(JSONObject().put("name", it).put("mnemonic", "m-$it")) } }

    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getString("name") }

    @Test
    fun removesOnlyThatWallet() {
        val (rest, _) = WalletList.remove(list("a", "b", "c"), 1, 0)
        assertEquals(listOf("a", "c"), names(rest))
        assertEquals("m-c", rest.getJSONObject(1).getString("mnemonic"))
    }

    @Test
    fun selectionFollowsTheWalletItNamed() {
        assertEquals(1, WalletList.remove(list("a", "b", "c"), 0, 2).second)
        assertEquals(0, WalletList.remove(list("a", "b", "c"), 2, 0).second)
    }

    @Test
    fun removingTheSelectedSelectsTheFirstLeft() {
        assertEquals(0, WalletList.remove(list("a", "b", "c"), 1, 1).second)
        assertEquals(0, WalletList.remove(list("a", "b"), 0, 7).second)
    }

    @Test
    fun theLastLeavesNothing() {
        val (rest, sel) = WalletList.remove(list("a"), 0, 0)
        assertEquals(0, rest.length())
        assertEquals(-1, sel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnIndexOutOfRange() {
        WalletList.remove(list("a"), 1, 0)
    }

    @Test
    fun leavesTheInputAlone() {
        val wallets = list("a", "b")
        WalletList.remove(wallets, 0, 0)
        assertEquals(listOf("a", "b"), names(wallets))
    }

    @Test
    fun renamesOnlyThatWalletAndKeepsItsPhrase() {
        val wallets = list("a", "b")
        val out = WalletList.rename(wallets, 1, "  Savings  ")
        assertEquals(listOf("a", "Savings"), names(out))
        assertEquals("m-b", out.getJSONObject(1).getString("mnemonic"))
        assertEquals(listOf("a", "b"), names(wallets))
    }

    @Test
    fun nameBounds() {
        assertEquals(null, WalletList.cleanName("   "))
        assertEquals(null, WalletList.cleanName("x".repeat(33)))
        assertEquals("x".repeat(32), WalletList.cleanName("x".repeat(32)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnEmptyName() {
        WalletList.rename(list("a"), 0, " ")
    }
}
