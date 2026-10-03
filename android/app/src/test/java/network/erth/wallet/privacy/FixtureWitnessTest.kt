package network.erth.wallet.privacy

import network.erth.wallet.privacy.prove.ActionWitness
import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.tx.PrivateMsgs
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The chain's own witness fixtures (tools/privacyfixtures membership,
 * tools/orchardfixtures action), rebuilt here with the wallet's trees,
 * derivations and Grumpkin. nargo execute accepting the chain's tomls is the
 * Go<->Noir parity check; these tests are the Go<->Kotlin one.
 */
class FixtureWitnessTest {

    private fun det(label: String, i: Long): Fr = Privacy.h(Privacy.assetId("fixture/$label"), Privacy.u64(i))

    private fun publicInputs(name: String): List<String> {
        val raw = Vectors.resource("privacy/$name/public_inputs.expected")
        return (0 until raw.size / 32).map { Vectors.hex(raw.copyOfRange(32 * it, 32 * it + 32)) }
    }

    /** Minimal TOML for Prover.toml: key = "scalar" | [ ... ] of strings, values normalised to integers. */
    private fun parseToml(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (k, v) = line.split(" = ", limit = 2)
            out[k.trim()] = parseValue(v.trim())
        }
        return out
    }

    private fun parseValue(v: String): Any {
        if (!v.startsWith("[")) return num(v.trim('"'))
        val inner = v.substring(1, v.length - 1).trim()
        if (inner.isEmpty()) return emptyList<Any>()
        if (inner.startsWith("[")) {
            val parts = ArrayList<String>()
            var depth = 0; var start = 0
            for ((i, c) in inner.withIndex()) {
                if (c == '[') { if (depth == 0) start = i; depth++ }
                if (c == ']') { depth--; if (depth == 0) parts.add(inner.substring(start, i + 1)) }
            }
            return parts.map(::parseValue)
        }
        return inner.split(",").map { num(it.trim().trim('"')) }
    }

    private fun num(s: String): BigInteger = if (s.startsWith("0x")) BigInteger(s.substring(2), 16) else BigInteger(s)

    @Test
    fun membershipFixture() {
        val t = MerkleTree(MemNodeStore())
        val ours = 13L
        val idSecret = det("id_secret", 0)
        val dscKey = det("dsc", 0)
        val country = Privacy.countryField("DE")
        val activatedAt = 1_790_000_000L
        for (i in 0L until 21) {
            val leaf = if (i == ours) Privacy.identityLeaf(Privacy.idc(idSecret), dscKey, country, activatedAt)
            else Privacy.identityLeaf(Privacy.idc(det("other", i)), det("dsc", i % 3), Privacy.countryField(listOf("DE", "FR", "")[(i % 3).toInt()]), 1_780_000_000 + i)
            t.append(leaf)
        }
        t.update(3, Fr.ZERO)
        val w = MembershipWitness(
            idSecret = idSecret, dscKey = dscKey, country = country, activatedAt = activatedAt,
            leafIndex = ours, siblings = t.path(ours), root = t.root(),
            scope = Privacy.assetId("claim:20360"), signal = det("signal", 0),
            excludedDsc = det("dsc", 99), excludedCountry = Privacy.countryField("FR"),
            maxActivation = activatedAt + 86_400,
        )
        w.check()
        assertEquals(publicInputs("fixture_membership"), w.publicInputs().map { it.toHex() })
        assertEquals(parseToml(String(Vectors.resource("privacy/fixture_membership/Prover.toml"))), parseToml(w.proverToml()))
    }

    /**
     * tools/orchardfixtures' 3-action mixed-asset bundle: each action's
     * witness rebuilt from its private inputs reproduces the chain's public
     * inputs (cv included) and Prover.toml, and the bundle's digest, sighash
     * and binding signature check under the wallet's own code.
     */
    @Test
    fun actionFixture() {
        val bj = org.json.JSONObject(String(Vectors.resource("privacy/fixture_action/bundle.json")))
        val acts = bj.getJSONArray("actions")
        val bundle = network.erth.earth.proto.shielded.Bundle.newBuilder()
        fun f(x: Any?): Fr = Fr.of(x as BigInteger)
        for (i in 0 until acts.length()) {
            val t = parseToml(String(Vectors.resource("privacy/fixture_action/action_$i/Prover.toml")))
            @Suppress("UNCHECKED_CAST")
            val w = ActionWitness(
                nk = f(t["nk"]), sAsset = f(t["s_asset"]), sValue = (t["s_value"] as BigInteger).toLong(),
                sRho = f(t["s_rho"]), sRcm = f(t["s_rcm"]), sPos = (t["s_pos"] as BigInteger).toLong(),
                sPath = (t["s_path"] as List<BigInteger>).map { Fr.of(it) },
                oAsset = f(t["o_asset"]), oValue = (t["o_value"] as BigInteger).toLong(), oPc = f(t["o_pc"]),
                rcv = f(t["rcv"]), anchor = f(t["anchor"]), sighash = f(t["sighash"]),
            )
            w.check()
            assertEquals("action $i", publicInputs("fixture_action/action_$i"), w.publicInputs().map { it.toHex() })
            assertEquals("action $i toml", t, parseToml(w.proverToml()))
            val a = acts.getJSONObject(i)
            assertEquals(a.getString("cv"), Vectors.hex(w.cv.toBytes()))
            bundle.addActions(
                network.erth.earth.proto.shielded.Action.newBuilder()
                    .setAnchor(bs(w.anchor.toBytes())).setNullifier(bs(w.nf.toBytes())).setCommitment(bs(w.cmOut.toBytes()))
                    .setCv(bs(w.cv.toBytes())).setCiphertext(bs(Vectors.unhex(a.getString("ct")))),
            )
        }
        val bal = bj.getJSONArray("balances")
        for (i in 0 until bal.length()) {
            val b = bal.getJSONObject(i)
            bundle.addBalances(network.erth.earth.proto.shielded.ValueBalance.newBuilder().setDenom(b.getString("denom")).setAmount(b.getLong("value")))
        }
        bundle.setBindingSig(bs(Vectors.unhex(bj.getString("binding_sig"))))
        val b = bundle.build()
        val tx = bj.getJSONObject("tx")
        val sighash = Privacy.signal(bj.getString("msg_type"), bj.getString("chain_id"), listOf(Privacy.u64(1), PrivateMsgs.digest(b),
            Privacy.bytes(tx.getString("memo").toByteArray()), Privacy.u64(tx.getLong("timeout_height")), Privacy.u64(tx.getLong("gas_limit"))))
        assertEquals(bj.getString("sighash").removePrefix("0x"), sighash.toHex())
        assertTrue(PrivateMsgs.checkBalance(b, sighash))
    }

    private fun bs(b: ByteArray) = com.google.protobuf.ByteString.copyFrom(b)
}
