package network.erth.wallet.privacy

import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.keys.ShieldedAddress
import network.erth.wallet.privacy.note.AssetDenoms
import network.erth.wallet.privacy.note.NoteCipher
import network.erth.wallet.privacy.note.NotePlaintext
import network.erth.wallet.privacy.zk.Fr
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
        // Owner-tag salts (PRIVACY_FORMATS.md §7), cross-checked with an independent Python HMAC
        // derivation.
        assertEquals("0685f54037389aaceee42288ed8c8c996a884e297ffee771c73370ca885e1618", keys.otagSalt(0).toHex())
        assertEquals("2e52e73b7af259664a34df8bcee1c0476009a37e0ab2e52b285bae497845a9ba", keys.otagSalt(1).toHex())
        assertEquals(Privacy.ownerTag(keys.ownerPk, keys.otagSalt(1)), keys.ownerTag(1))
    }

    @Test
    fun addressRoundTrip() {
        val s = keys.address.encode()
        assertTrue(s.startsWith("erthz1"))
        assertEquals(116, s.length)
        assertEquals(keys.address, ShieldedAddress.decode(s))
        assertEquals(keys.address, ShieldedAddress.decode(s.uppercase()))
        // A flipped character fails the checksum.
        val bad = s.substring(0, 20) + (if (s[20] == 'q') 'p' else 'q') + s.substring(21)
        assertThrows(IllegalArgumentException::class.java) { ShieldedAddress.decode(bad) }
        assertTrue(!ShieldedAddress.isShielded("earth1qqqsyqcyq5rqwzqfpg9scrgwpugpzysncc2uls"))
    }

    @Test
    fun addressRejects() {
        fun enc(payload: ByteArray) = network.erth.wallet.privacy.keys.Bech32m.encode(
            "erthz", ShieldedAddress.convertBits(payload, 8, 5, true))
        val good = keys.address.payload()
        assertEquals(keys.address, ShieldedAddress.decode(enc(good)))
        // Unknown version.
        assertThrows(IllegalArgumentException::class.java) { ShieldedAddress.decode(enc(byteArrayOf(2) + good.copyOfRange(1, 65))) }
        // Wrong length.
        assertThrows(IllegalArgumentException::class.java) { ShieldedAddress.decode(enc(good.copyOf(64))) }
        // Non-canonical owner_pk (the modulus itself).
        val p = Fr.MODULUS.toByteArray().let { it.copyOfRange(it.size - 32, it.size) }
        assertThrows(IllegalArgumentException::class.java) { ShieldedAddress.decode(enc(byteArrayOf(1) + p + good.copyOfRange(33, 65))) }
        // Another hrp.
        assertThrows(IllegalArgumentException::class.java) {
            ShieldedAddress.decode(network.erth.wallet.privacy.keys.Bech32m.encode("erth", ShieldedAddress.convertBits(good, 8, 5, true)))
        }
    }

    @Test
    fun noteEncryption() {
        val denom = "derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val n = NotePlaintext.fresh(denom, 123_456_789, "hi".toByteArray())
        val cm = n.cm(keys.ownerPk)
        val ct = NoteCipher.encrypt(n, keys.address)
        assertEquals(NoteCipher.CIPHERTEXT_BYTES, ct.size)
        assertEquals(217, ct.size)
        assertEquals(n, NoteCipher.tryDecrypt(ct, cm, keys, AssetDenoms(listOf(denom))))
        // Unknown denom: kept by asset id, same note otherwise.
        val unresolved = NoteCipher.tryDecrypt(ct, cm, keys)!!
        assertEquals("asset/" + Privacy.assetId(denom).toHex(), unresolved.denom)
        assertEquals(cm, unresolved.cm(keys.ownerPk))
        assertNull(NoteCipher.tryDecrypt(ct, cm, other))
        // Bound to its cm.
        assertNull(NoteCipher.tryDecrypt(ct, Privacy.cm(n.asset, n.value + 1, n.pc(keys.ownerPk)), keys))
        // Tampering anywhere fails the tag.
        for (i in listOf(0, 31, 32, 40, ct.size - 1)) {
            val t = ct.copyOf(); t[i] = (t[i].toInt() xor 1).toByte()
            assertNull(NoteCipher.tryDecrypt(t, cm, keys))
        }
        assertEquals(ct.size, NoteCipher.dummy().size)
        assertTrue(ct.size <= 1024) // x/shielded MaxCiphertextBytes
    }

    /**
     * Golden: a fixed esk, note and address, so the chain's and the web app's
     * codecs can be checked against this one byte for byte.
     */
    @Test
    fun noteCiphertextGolden() {
        val n = NotePlaintext("uanml", 1_000_000, Vectors.fe(7), Vectors.fe(8), "memo".toByteArray())
        val esk = ByteArray(32) { (it + 1).toByte() }
        val ct = NoteCipher.encryptWith(esk, n, keys.address)
        assertEquals(NOTE_CT_GOLDEN, Vectors.hex(ct))
        assertEquals(n, NoteCipher.tryDecrypt(ct, n.cm(keys.ownerPk), keys))
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
            "erthz1qyh7prm54w0lu9ymzm3dtpm3r3juewetjuu8675hw0gpu5hywx4ll33j03sqfqzdec0ad2rke8ycxgzvkfg4q7ja4zhgghjjeh59s4mn9gwhg2"
        // esk = 01..20, note (uanml, 1000000, rho = fe(7), rcm = fe(8), memo "memo")
        // to KNOWN_ADDRESS; cross-checked with Python cryptography (x25519,
        // HKDF-SHA256, ChaCha20-Poly1305) and a reference bech32m.
        const val NOTE_CT_GOLDEN =
            "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c27a4ccf6eb32e22c660248ac5cfc37d11ce8" +
                "70fdb324e97e1e94176876b187056319e704577b33ba5fa53a834ac83f419c51cbea350859acf194fd7c69dc74074d503714" +
                "b4783349656cd5427e678cfec273deb29a786db6ca40b1b95cf5a5356bab620aa7657442f8d130c415aa7c9bfdbc70094c92" +
                "7ecc8448cd159c76fb0d937b4af129915b6eee36c1066dfd4e66ccbd1fa244c0ce319a0cc12095a58d894477a8194318471c" +
                "9d1365d3eb99e330944de6893064b50614"
    }
}
