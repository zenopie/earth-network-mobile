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
            val s1 = PrivacyStore.shared(dir, "w")
            assertSame(s1, PrivacyStore.shared(dir, "w"))
            s1.state.pendingUnbonds.add(network.erth.wallet.privacy.sync.PendingUnbond("AB", "v", 42, Fr.ONE, 1, 9))
            s1.save()
            assertEquals(42L, PrivacyStore.shared(dir, "w").state.pendingUnbonds.single().derth)
            PrivacyStore.delete(dir, "w")
            assertTrue(PrivacyStore.shared(dir, "w") !== s1)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun forgettingAWalletDeletesItsPrivateData() {
        val dir = tmp()
        val chain = FakeChain()
        val a = wallet(chain, store = PrivacyStore.open(dir, "w1"))
        funded(chain, a)
        a.sync()
        PrivacyStore.open(dir, "w2").save()
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
        PrivacyStore.open(dir, "w").save()
        File(dir, "privacy/w/state.json").writeText("{\"notes\": [")
        assertThrows(PrivacyStore.CorruptState::class.java) { PrivacyStore.open(dir, "w") }
    }

    @Test
    fun aSaveThatFailsThrows() {
        val dir = tmp()
        val s = PrivacyStore.open(dir, "w")
        File(dir, "privacy/w/state.json.tmp").mkdirs()
        assertThrows(java.io.IOException::class.java) { s.save() }
    }
}
