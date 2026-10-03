package network.erth.wallet.privacy

import network.erth.earth.proto.shielded.Action
import network.erth.earth.proto.shielded.Bundle
import network.erth.earth.proto.shielded.ValueBalance
import network.erth.wallet.privacy.Vectors.fr
import network.erth.wallet.privacy.Vectors.hex
import network.erth.wallet.privacy.Vectors.json
import network.erth.wallet.privacy.Vectors.unhex
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.Grumpkin
import network.erth.wallet.privacy.zk.Privacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Grumpkin, hash to curve, value commitments and the binding signature against chain zk/orchard. */
class OrchardVectorsTest {
    private val o = json.getJSONObject("orchard")

    private fun pt(j: JSONObject) = Grumpkin.Point(fr(j.getString("x")), fr(j.getString("y")))

    @Test
    fun curveAndR() {
        assertEquals(BigInteger(o.getString("n")), Grumpkin.N)
        val r = pt(o.getJSONObject("r"))
        assertEquals(r, Grumpkin.R)
        assertEquals(0, o.getInt("r_ctr"))
        assertTrue(r.isOnCurve())
        assertTrue(Grumpkin.Point.INFINITY.isOnCurve())
        assertEquals(Grumpkin.Point.INFINITY, Grumpkin.R * Grumpkin.N)
        assertEquals(Grumpkin.R, Grumpkin.R * Grumpkin.N.add(BigInteger.ONE))
    }

    @Test
    fun hashToCurveCanonicalY() {
        val hs = o.getJSONArray("h2c")
        for (i in 0 until hs.length()) {
            val h = hs.getJSONObject(i)
            val tag = fr(h.getString("tag"))
            val input = fr(h.getString("input"))
            val (p, ctr) = Grumpkin.hashToPoint(tag, input)
            assertEquals("ctr $i", h.getInt("ctr"), ctr)
            assertEquals("point $i", pt(h.getJSONObject("point")), p)
            assertTrue(p.y.toBigInteger() <= Grumpkin.HALF_P)
            val misses = h.optJSONArray("misses")
            for (k in 0 until (misses?.length() ?: 0)) assertNull(Grumpkin.hashToPointAt(tag, input, misses!!.getInt(k)))
        }
        val bases = o.getJSONObject("bases")
        for (d in bases.keys()) assertEquals(d, pt(bases.getJSONObject(d)), Grumpkin.valueBase(Privacy.assetId(d)))
    }

    @Test
    fun pointArithmetic() {
        val a = o.getJSONObject("add")
        val g = pt(a.getJSONObject("a"))
        val h = pt(a.getJSONObject("b"))
        assertEquals(pt(a.getJSONObject("sum")), g + h)
        assertEquals(pt(a.getJSONObject("dbl")), g + g)
        assertEquals(pt(a.getJSONObject("a_minus_b")), g - h)
        assertEquals(Grumpkin.Point.INFINITY, g - g)
        assertEquals(pt(a.getJSONObject("a_minus_a")), Grumpkin.Point.INFINITY)
        val ms = o.getJSONArray("mul")
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            assertEquals("mul $i", pt(m.getJSONObject("out")), pt(m.getJSONObject("point")) * BigInteger(m.getString("scalar"), 16))
        }
        assertEquals(g, g + Grumpkin.Point.INFINITY)
        assertEquals(g * BigInteger.valueOf(3), g + g + g)
    }

    @Test
    fun valueCommitments() {
        val vs = o.getJSONArray("value_commit")
        for (i in 0 until vs.length()) {
            val v = vs.getJSONObject(i)
            val cv = Grumpkin.valueCommit(
                Privacy.assetId(v.getString("s_denom")), java.lang.Long.parseUnsignedLong(v.getString("s_value")),
                Privacy.assetId(v.getString("o_denom")), java.lang.Long.parseUnsignedLong(v.getString("o_value")),
                fr(v.getString("rcv")),
            )
            assertEquals("cv $i", v.getString("cv"), hex(cv.toBytes()))
        }
    }

    @Test
    fun bindingSignatureByteExact() {
        val b = json.getJSONObject("binding")
        val acts = b.getJSONArray("actions")
        val rcvs = (0 until acts.length()).map { fr(acts.getJSONObject(it).getString("rcv")) }
        val bundle = Bundle.newBuilder()
        for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val cv = Grumpkin.valueCommit(
                Privacy.assetId(a.getString("s_denom")), a.getString("s_value").toLong(),
                Privacy.assetId(a.getString("o_denom")), a.getString("o_value").toLong(), rcvs[i],
            )
            assertEquals(a.getString("cv"), hex(cv.toBytes()))
            bundle.addActions(
                Action.newBuilder()
                    .setAnchor(bs(fr(a.getString("anchor")).toBytes())).setNullifier(bs(fr(a.getString("nf")).toBytes()))
                    .setCommitment(bs(fr(a.getString("cm")).toBytes())).setCv(bs(cv.toBytes())).setCiphertext(bs(unhex(a.getString("ct")))),
            )
        }
        bundle.addBalances(ValueBalance.newBuilder().setDenom("uerth").setAmount(10_000))
        val digest = PrivateMsgs.digest(bundle.build())
        assertEquals(b.getString("digest"), digest.toHex())
        // Sighash with empty tx fields: Bytes(""), timeout 0, gas 0 after the digests.
        val noTx = listOf(Privacy.bytes(ByteArray(0)), Privacy.u64(0), Privacy.u64(0))
        val sighash = Privacy.signal(PrivateMsgs.SEND, "earth-1", listOf(Privacy.u64(1), digest) + noTx + listOf(Privacy.bytes(ByteArray(0)), Privacy.u64(10_000)))
        assertEquals(b.getString("sighash"), sighash.toHex())
        // The tx's memo (UTF-8), timeout_height and gas_limit are bound (audit M1).
        val t = b.getJSONObject("sighash_tx")
        val withTx = Privacy.signal(PrivateMsgs.SEND, "earth-1", listOf(Privacy.u64(1), digest,
            Privacy.bytes(t.getString("memo").toByteArray(Charsets.UTF_8)), Privacy.u64(t.getLong("timeout_height")), Privacy.u64(t.getLong("gas_limit")),
            Privacy.bytes(ByteArray(0)), Privacy.u64(10_000)))
        assertEquals(t.getString("sighash"), withTx.toHex())

        val bsk = Grumpkin.bindingKey(rcvs)
        assertEquals(b.getString("bsk"), "%064x".format(bsk))
        val sig0 = Grumpkin.signBinding(bsk, sighash, ByteArray(32))
        assertEquals(b.getString("sig_rnd0"), hex(sig0))
        val sig1 = Grumpkin.signBinding(bsk, sighash, unhex(b.getString("rnd1")))
        assertEquals(b.getString("sig_rnd1"), hex(sig1))

        val signed = bundle.setBindingSig(bs(sig0)).build()
        val bvk = PrivateMsgs.bindingKey(signed)
        assertEquals(pt(b.getJSONObject("bvk")), bvk)
        assertEquals(Grumpkin.R * bsk, bvk)
        assertTrue(PrivateMsgs.checkBalance(signed, sighash))
        assertFalse(PrivateMsgs.checkBalance(signed, sighash + Fr.ONE))
        // A balance that overstates the release no longer balances.
        val more = signed.toBuilder().setBalances(0, ValueBalance.newBuilder().setDenom("uerth").setAmount(10_001)).build()
        assertFalse(PrivateMsgs.checkBalance(more, sighash))
        // s >= n is refused (one signature, one encoding).
        val s = BigInteger(1, sig0.copyOfRange(64, 96)).add(Grumpkin.N)
        val malleable = sig0.copyOf(64) + s.toByteArray().takeLast(32).toByteArray()
        if (s.bitLength() <= 256) assertFalse(Grumpkin.verifyBinding(bvk, sighash, malleable))

        val pm1 = Fr.ZERO - Fr.ONE
        assertEquals(b.getString("bsk_wrap"), "%064x".format(Grumpkin.bindingKey(listOf(pm1, pm1, pm1))))
    }

    private fun bs(b: ByteArray) = com.google.protobuf.ByteString.copyFrom(b)
}
