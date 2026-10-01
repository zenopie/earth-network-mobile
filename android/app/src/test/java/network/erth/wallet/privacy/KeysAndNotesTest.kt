package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysAndNotesTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val keys = PrivacyKeys.fromMnemonic(mnemonic)
    private val other = PrivacyKeys.fromMnemonic("legal winner thank year wave sausage worth useful legal winner thank yellow")

    /**
     * Pinned: these are the wallet's own definitions (PRIVACY_FORMATS.md), and
     * a change here strands every user's notes and registration. The iOS port
     * must produce the same values from the same words.
     */
    @Test
    fun derivationIsPinned() {
        assertEquals(KNOWN_ID_SECRET, keys.idSecret.toHex())
        assertEquals(KNOWN_NK, keys.nk.toHex())
        assertEquals(KNOWN_EK_PUB, Vectors.hex(keys.ekPub))
        assertEquals(KNOWN_ADDRESS, keys.address.encode())
        assertEquals(Privacy.idc(keys.idSecret), keys.idc)
        assertNotEquals(keys.idSecret, other.idSecret)
        assertNotEquals(keys.positionKey(0).pubKeyPoint, keys.positionKey(1).pubKeyPoint)
    }

    @Test
    fun addressRoundTrip() {
        val s = keys.address.encode()
        assertTrue(s.startsWith("erth1z"))
        assertEquals(115, s.length)
        assertEquals(keys.address, ShieldedAddress.decode(s))
        assertEquals(keys.address, ShieldedAddress.decode(s.uppercase()))
        // A flipped character fails the checksum.
        val bad = s.substring(0, 20) + (if (s[20] == 'q') 'p' else 'q') + s.substring(21)
        assertThrows(IllegalArgumentException::class.java) { ShieldedAddress.decode(bad) }
        assertTrue(!ShieldedAddress.isShielded("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"))
    }

    @Test
    fun noteEncryption() {
        val n = NotePlaintext.fresh("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq", 123_456_789, "hi".toByteArray())
        val ct = NoteCipher.encrypt(n, keys.address)
        assertEquals(NoteCipher.CIPHERTEXT_BYTES, ct.size)
        assertEquals(n, NoteCipher.tryDecrypt(ct, keys))
        assertNull(NoteCipher.tryDecrypt(ct, other))
        // Tampering anywhere fails the tag.
        for (i in listOf(0, 1, 40, ct.size - 1)) {
            val t = ct.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertNull(NoteCipher.tryDecrypt(t, keys))
        }
        // Same length whatever the denom: the length says nothing about the asset.
        assertEquals(ct.size, NoteCipher.encrypt(NotePlaintext.fresh("uerth", 1), keys.address).size)
        assertEquals(ct.size, NoteCipher.dummy().size)
        assertNull(NoteCipher.tryDecrypt(NoteCipher.dummy(), keys))
        assertTrue(ct.size <= 1024) // x/shielded MaxCiphertextBytes
    }

    companion object {
        // Cross-checked against an independent Python BIP-32/HMAC/x25519
        // derivation when first pinned.
        const val KNOWN_ID_SECRET =
            "059b96926ae7a563f2ddeb6fe425a6ccdd1c267f1d06d92457f1b6dba741fcba"
        const val KNOWN_NK =
            "0a67906d75dbdf06237678494da622b51aab4bcefea0134f5ead80d7c9440b81"
        const val KNOWN_EK_PUB =
            "c6327c6004804dce1fd6a876c9c983204cb251507a5da8ae845e52cde8585773"
        const val KNOWN_ADDRESS =
            "erth1z9lsg7a9tnllpfxckut2cwuguvhxtk2uh8p7h49mn6q099er340luvvnuvqzgqnwwrlt2sakfexpjqn9j29g85hdg46z9u5kdapv9wucnhnae3"
    }
}
