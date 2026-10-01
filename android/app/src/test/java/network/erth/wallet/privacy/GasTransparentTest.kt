package network.erth.wallet.privacy

import network.erth.wallet.crypto.Bech32
import network.erth.wallet.privacy.keys.PrivacyKeys
import network.erth.wallet.privacy.sync.PrivacyStore
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class GasTransparentTest {
    private val alice = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    /** Pinned by the chain's zk/privacy TestGasScopeAndSignalPinned. */
    @Test
    fun scopeAndSignalMatchTheChain() {
        assertEquals("189ca0017ef0d3fb8ebca623f3a5b50b0db38b16ff09ed877b8ed66a9548c9ff", Privacy.gasScope(202610).toHex())
        val addr = ByteArray(20) { (it + 1).toByte() }
        assertEquals(
            "1985e8e50ba97e2b2a44119f927d4c6ea9d58f8cafa89c8c2a60eabe3ba29c80",
            Privacy.gasTransparentSignal("earth-1", addr).toHex(),
        )
    }

    @Test
    fun monthIsUtcYyyymm() {
        assertEquals(202610L, GasTransparent.month(1_790_812_800L)) // 2026-10-01T00:00:00Z
        assertEquals(202609L, GasTransparent.month(1_790_812_799L))
        assertEquals(202612L, GasTransparent.month(1_798_761_599L)) // 2026-12-31T23:59:59Z
    }

    @Test
    fun witnessProvesTheWalletsLeafForThisMonthAndAddress() {
        val chain = FakeChain()
        val a = PrivacyWallet(PrivacyKeys.fromMnemonic(alice), PrivacyStore.memory(), chain, chain,
            object : PrivacyChainReads {
                override fun personhoodParams() = PrivacyChainReads.PersonhoodParams(30L * 86_400, 3_600)
                override fun ballotInputs(proposalId: Long, optionId: Long) = error("unused")
                override fun epochNumber() = 0L
                override fun snapshot(proposalId: Long) = error("unused")
                override fun positions() = emptyList<PrivacyChainReads.Position>()
            },
            chain.prover, chain.chainId, now = { chain.now })
        a.sync()
        val target = Bech32.encode("earth", Bech32.convertBits(ByteArray(20) { 9 }, 8, 5, true))

        // Not registered: nothing to prove.
        assertThrows(IllegalStateException::class.java) { GasTransparent.witness(a, target, chain.now) }

        val prep = a.prepareRegistration(null)
        chain.shield("uerth", 100_000, prep.gas.pc)
        a.sync()
        val signals = listOf("261001", prep.binding.toBigInteger().toString(), "123456789", Fr.of(77).toBigInteger().toString())
        a.register(prep, ByteArray(14_656), signals, "lean_poa", ByteArray(10))
        a.sync()

        // Activated this instant: max_activation (an hour boundary, less the margin) is before it.
        assertThrows(PrivacyWallet.NotYet::class.java) { GasTransparent.witness(a, target, chain.now) }

        val now = chain.now + 2 * 3600
        val w = GasTransparent.witness(a, target, now)
        assertEquals(Privacy.gasScope(GasTransparent.month(now)), w.scope)
        assertEquals(Privacy.gasTransparentSignal("earth-1", PrivateMsgs.addressBytes(target)), w.signal)
        assertEquals(Fr.ZERO, w.excludedDsc)
        assertEquals(Fr.ZERO, w.excludedCountry)
        assertEquals(0L, w.maxActivation % 3600)
        assertEquals(chain.identityTree.root(), w.root)
        assertEquals(Privacy.scopeNullifier(a.keys.idSecret, w.scope), w.nullifier)

        val req = GasTransparent.request(a, target, { ByteArray(3) { 7 } }, now)
        val body = GasTransparent.body(req)
        assertEquals(target, body.getString("address"))
        assertEquals("BwcH", body.getString("proof"))
        assertArrayEquals(w.root.toBytes(), Base64.getDecoder().decode(body.getString("root")))
        assertArrayEquals(w.nullifier.toBytes(), Base64.getDecoder().decode(body.getString("nullifier")))
        assertEquals(w.maxActivation, body.getLong("max_activation"))
        assertEquals(GasTransparent.month(now), body.getLong("month"))
    }
}
