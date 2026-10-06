package network.erth.wallet.privacy

import java.io.File
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.zk.Fr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallet's private data on disk: one store per wallet directory in the
 * process, saves that fail loudly, a corrupt state that is an error, and
 * forgetting a wallet.
 */
class PrivacyStoreTest : WalletTest() {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("privacy-store").toFile()

    @Test
    fun theProcessHoldsOneStorePerWalletDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("a6").toFile()
        try {
            val s1 = PrivacyStore.shared(dir, "w", testDataKey)
            assertSame(s1, PrivacyStore.shared(dir, "w", testDataKey))
            s1.state.pendingUnbonds.add(network.erth.wallet.privacy.sync.PendingUnbond("AB", "v", 42, Fr.ONE, 1, 9))
            s1.save()
            assertEquals(42L, PrivacyStore.shared(dir, "w", testDataKey).state.pendingUnbonds.single().derth)
            PrivacyStore.delete(dir, "w")
            assertTrue(PrivacyStore.shared(dir, "w", testDataKey) !== s1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun forgettingAWalletDeletesItsPrivateData() {
        val dir = tmp()
        val chain = FakeChain()
        val a = wallet(chain, store = PrivacyStore.open(dir, "w1", testDataKey))
        funded(chain, a)
        a.sync()
        PrivacyStore.open(dir, "w2", testDataKey).save()
        assertTrue(File(dir, "privacy/w1/state.json").exists())
        PrivacyStore.delete(dir, "w1")
        assertFalse(File(dir, "privacy/w1").exists())
        assertTrue(File(dir, "privacy/w2").exists())
        PrivacyStore.delete(dir)
        assertFalse(File(dir, "privacy").exists())
    }

    @Test
    fun corruptStateIsAnErrorNotAnEmptyWallet() {
        val dir = tmp()
        PrivacyStore.open(dir, "w", testDataKey).save()
        File(dir, "privacy/w/state.json").writeText("{\"notes\": [")
        assertThrows(PrivacyStore.CorruptState::class.java) { PrivacyStore.open(dir, "w", testDataKey) }
    }

    @Test
    fun aSaveThatFailsThrows() {
        val dir = tmp()
        val s = PrivacyStore.open(dir, "w", testDataKey)
        File(dir, "privacy/w/state.json.tmp").mkdirs()
        assertThrows(java.io.IOException::class.java) { s.save() }
    }

    @Test
    fun stateIsSealedOnDisk() {
        val dir = tmp()
        val s = PrivacyStore.open(dir, "w", testDataKey)
        s.state.chainId = "earth-1"
        s.state.handle = "alice"
        s.save()
        val raw = File(dir, "privacy/w/state.json").readText()
        assertFalse(raw.contains("earth-1"))
        assertFalse(raw.contains("alice"))
        assertTrue(raw.contains("\"sealed\""))
        assertEquals("alice", PrivacyStore.open(dir, "w", testDataKey).state.handle)
    }

    @Test
    fun plaintextFromBeforeSealingIsReadAndSealed() {
        val dir = tmp()
        File(dir, "privacy/w").mkdirs()
        val legacy = network.erth.wallet.privacy.sync.PrivacyState().apply { handle = "bob" }
        File(dir, "privacy/w/state.json").writeText(legacy.toJson().toString())
        assertEquals("bob", PrivacyStore.open(dir, "w", testDataKey).state.handle)
        assertFalse(File(dir, "privacy/w/state.json").readText().contains("bob"))
        assertEquals("bob", PrivacyStore.open(dir, "w", testDataKey).state.handle)
    }

    @Test
    fun anotherInstallsSealedStateIsDropped() {
        val dir = tmp()
        PrivacyStore.open(dir, "w", testDataKey).apply { state.handle = "carol"; save() }
        val other = ByteArray(32) { 7 }
        val s = PrivacyStore.open(dir, "w", other)
        assertEquals("", s.state.handle)
        assertEquals("", PrivacyStore.open(dir, "w", other).state.handle)
    }

    @Test
    fun aDamagedOrMovedSealIsAnError() {
        val dir = tmp()
        PrivacyStore.open(dir, "w", testDataKey).apply { state.handle = "dave"; save() }
        // Another wallet's directory: the wallet id is bound in.
        File(dir, "privacy/v").mkdirs()
        File(dir, "privacy/w/state.json").copyTo(File(dir, "privacy/v/state.json"))
        assertThrows(PrivacyStore.CorruptState::class.java) { PrivacyStore.open(dir, "v", testDataKey) }
        // A flipped ciphertext byte.
        val f = File(dir, "privacy/w/state.json")
        val j = org.json.JSONObject(f.readText())
        val ct = j.getString("ct")
        j.put("ct", (if (ct[0] == '0') "1" else "0") + ct.substring(1))
        f.writeText(j.toString())
        assertThrows(PrivacyStore.CorruptState::class.java) { PrivacyStore.open(dir, "w", testDataKey) }
    }
}
