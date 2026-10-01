package network.erth.wallet.privacy

import network.erth.wallet.privacy.prove.MembershipWitness
import network.erth.wallet.privacy.prove.TransferInput
import network.erth.wallet.privacy.prove.TransferOutput
import network.erth.wallet.privacy.prove.TransferWitness
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * The chain's own witness fixtures (tools/privacyfixtures), rebuilt here from
 * scratch with the wallet's trees and derivations. Their public inputs are
 * byte for byte those of the real proofs in the chain's
 * zk/ultrahonk/testdata/{membership,transfer} (gen.sh checks), so a witness
 * these builders produce is one the chain verifies.
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

    @Test
    fun transferFixture() {
        val t = MerkleTree(MemNodeStore())
        val nk = det("nk", 0)
        val opk = Privacy.ownerPk(nk)
        val assetA = Privacy.assetId("uanml")
        val erth = Privacy.assetId("uerth")
        val inVal = longArrayOf(700_000, 300_000, 50_000)
        val outVal = longArrayOf(600_000, 350_000, 40_000)
        val assets = listOf(assetA, assetA, erth)
        val positions = longArrayOf(4, 7, 9)
        val rho = (0L until 3).map { det("rho", it) }
        val rcm = (0L until 3).map { det("rcm", it) }
        var next = 0
        for (p in 0L until 12) {
            var cm = Privacy.cm(erth, p + 1, det("otherpc", p))
            if (next < 3 && p == positions[next]) {
                cm = Privacy.cm(assets[next], inVal[next], Privacy.pc(opk, rho[next], rcm[next])); next++
            }
            t.append(cm)
        }
        val w = TransferWitness(
            asset = assetA, nk = nk,
            inputs = (0 until 3).map { TransferInput(inVal[it], rho[it], rcm[it], positions[it], t.path(positions[it])) },
            outputs = (0 until 3).map {
                TransferOutput(outVal[it], Privacy.pc(Privacy.ownerPk(det("recipient", it.toLong())), det("orho", it.toLong()), det("orcm", it.toLong())))
            },
            root = t.root(), fee = 10_000, vPubOut = 50_000, signal = det("signal", 1),
        )
        assertEquals(publicInputs("fixture_transfer"), w.publicInputs().map { it.toHex() })
        assertEquals(parseToml(String(Vectors.resource("privacy/fixture_transfer/Prover.toml"))), parseToml(w.proverToml()))
    }
}
