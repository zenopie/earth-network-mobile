package network.erth.wallet.privacy

import network.erth.wallet.privacy.Vectors.fe
import network.erth.wallet.privacy.Vectors.fr
import network.erth.wallet.privacy.Vectors.json
import network.erth.wallet.privacy.Vectors.unhex
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.FileNodeStore
import network.erth.wallet.privacy.zk.Poseidon2
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.nio.file.Files

/** Poseidon2, zk/privacy and zk/merkle against the chain's own outputs. */
class ZkVectorsTest {

    @Test
    fun fieldArithmetic() {
        val p = Fr.MODULUS
        val r = java.util.Random(7)
        repeat(200) {
            val a = BigInteger(256, r).mod(p)
            val b = BigInteger(256, r).mod(p)
            assertEquals(a.multiply(b).mod(p), (Fr.of(a) * Fr.of(b)).toBigInteger())
            assertEquals(a.add(b).mod(p), (Fr.of(a) + Fr.of(b)).toBigInteger())
            assertEquals(a.subtract(b).mod(p), (Fr.of(a) - Fr.of(b)).toBigInteger())
        }
        val pm1 = p.subtract(BigInteger.ONE)
        assertEquals(BigInteger.ONE, (Fr.of(pm1) * Fr.of(pm1)).toBigInteger())
        assertThrows(IllegalArgumentException::class.java) { Fr.fromBytes(p.toByteArray().takeLast(32).toByteArray()) }
        assertEquals(BigInteger("18446744073709551615"), Fr.ofU64(-1L).toBigInteger())
    }

    @Test
    fun poseidon2() {
        val vs = json.getJSONArray("poseidon2")
        for (i in 0 until vs.length()) {
            val v = vs.getJSONObject(i)
            val ins = v.getJSONArray("in")
            val inputs = (0 until ins.length()).map { fr(ins.getString(it)) }
            assertEquals("arity ${inputs.size}", v.getString("out"), Poseidon2.hash(inputs).toHex())
        }
    }

    @Test
    fun tagsAssetsBytesCountry() {
        val tags = json.getJSONObject("tags")
        val mine = mapOf(
            "id" to Privacy.TAG_ID, "owner" to Privacy.TAG_OWNER, "leaf" to Privacy.TAG_LEAF, "sn" to Privacy.TAG_SN,
            "pc" to Privacy.TAG_PC, "cm" to Privacy.TAG_CM, "nf" to Privacy.TAG_NF, "reg" to Privacy.TAG_REG,
            "asset" to Privacy.TAG_ASSET, "signal" to Privacy.TAG_SIGNAL, "bytes" to Privacy.TAG_BYTES, "scope" to Privacy.TAG_SCOPE,
        )
        assertEquals(tags.length(), mine.size)
        mine.forEach { (k, v) -> assertEquals(k, tags.getString(k), v.toHex()) }

        val assets = json.getJSONObject("asset_ids")
        for (d in assets.keys()) assertEquals(d, assets.getString(d), Privacy.assetId(d).toHex())
        assertEquals(assets.getString("uerth"), Privacy.ASSET_ERTH.toHex())

        val bytes = json.getJSONObject("bytes")
        for (h in bytes.keys()) assertEquals(h, bytes.getString(h), Privacy.bytes(unhex(h)).toHex())

        val c = json.getJSONObject("country")
        for (k in c.keys()) assertEquals(k, c.getString(k), Privacy.countryField(k).toHex())
    }

    @Test
    fun derivations() {
        val d = json.getJSONObject("derive")
        val idSecret = fr(d.getString("id_secret"))
        val nk = fr(d.getString("nk"))
        val rho = fr(d.getString("rho"))
        val rcm = fr(d.getString("rcm"))
        assertEquals(fe(1001), idSecret)
        val idc = Privacy.idc(idSecret)
        assertEquals(d.getString("idc"), idc.toHex())
        val opk = Privacy.ownerPk(nk)
        assertEquals(d.getString("owner_pk"), opk.toHex())
        assertEquals(d.getString("leaf"), Privacy.identityLeaf(idc, fr(d.getString("leaf_dsc")), Privacy.countryField("DE"), 1_790_000_000).toHex())
        assertEquals(d.getString("sn"), Privacy.scopeNullifier(idSecret, Privacy.claimScope(20360)).toHex())
        val pc = Privacy.pc(opk, rho, rcm)
        assertEquals(d.getString("pc"), pc.toHex())
        assertEquals(d.getString("cm"), Privacy.cm(Privacy.assetId("uanml"), 1_000_000, pc).toHex())
        assertEquals(d.getString("nf"), Privacy.nf(nk, rho, 4_000_000_000).toHex())
        assertEquals(d.getString("reg_none"), Privacy.registrationBinding(idc, fe(1), fe(2), Fr.ZERO).toHex())
    }

    @Test
    fun scopes() {
        val s = json.getJSONObject("scopes")
        assertEquals(s.getString("claim_20360"), Privacy.claimScope(20360).toHex())
        assertEquals(s.getString("caretaker"), Privacy.caretakerScope().toHex())
        assertEquals(s.getString("referrer"), Privacy.referrerScope().toHex())
        assertEquals(s.getString("proposal_5_0"), Privacy.proposalScope(5, 0).toHex())
        assertEquals(s.getString("proposal_5_1"), Privacy.proposalScope(5, 1).toHex())
        assertEquals(s.getString("removal_3"), Privacy.removalScope(3).toHex())
        assertEquals(s.getString("propose_removal_2_100"), Privacy.proposeRemovalScope(2, 100).toHex())
    }

    @Test
    fun signals() {
        val s = json.getJSONObject("signals")
        val cts = listOf("a".toByteArray(), "bb".toByteArray(), ByteArray(0))
        val recv = ByteArray(20) { (1 + it).toByte() }
        assertEquals(s.getString("transfer_send"), Privacy.transferSignal("earth-1", null, cts, 0).toHex())
        assertEquals(s.getString("transfer_unshield"), Privacy.transferSignal("earth-1", recv, cts, 77).toHex())
        val nfs = listOf(fe(1), fe(2), fe(3))
        assertEquals(s.getString("action"), Privacy.actionSignal("/x.y.Msg", "earth-1", cts, nfs, listOf(Fr.of(9))).toHex())
        assertEquals(
            s.getString("multi"),
            Privacy.multiSpendSignal(
                "/x.y.Msg", "earth-1",
                listOf(cts, listOf("c".toByteArray(), ByteArray(0), ByteArray(0))),
                listOf(nfs, listOf(fe(4), fe(5), fe(6))), listOf(Fr.of(9)),
            ).toHex(),
        )
    }

    private fun checkTree(t: MerkleTree, appendOneByOne: Boolean) {
        val m = json.getJSONObject("merkle")
        val zeros = m.getJSONArray("zeros")
        for (i in 0..Merkle.DEPTH) assertEquals(zeros.getString(i), Merkle.ZERO[i].toHex())
        val roots = m.getJSONObject("roots")
        val seed = m.getLong("leaf_seed")
        var n = 0
        val checkpoints = roots.keys().asSequence().map { it.toInt() }.sorted().toList()
        for (cp in checkpoints) {
            val batch = (n until cp).map { fe(seed + it) }
            if (appendOneByOne) batch.forEach { t.append(it) } else t.appendAll(batch)
            n = cp
            assertEquals("root at $cp", roots.getString("$cp"), t.root().toHex())
        }
        val paths = m.getJSONObject("paths")
        for (k in paths.keys()) {
            val want = paths.getJSONArray(k)
            val got = t.path(k.toLong())
            for (i in 0 until Merkle.DEPTH) assertEquals("path $k[$i]", want.getString(i), got[i].toHex())
            assertEquals(t.root(), Merkle.rootFromPath(t.leaf(k.toLong()), k.toLong(), got))
        }
        t.update(5, Fr.ZERO)
        assertEquals(m.getString("root_37_zeroed_5"), t.root().toHex())
    }

    @Test
    fun merkleIncremental() = checkTree(MerkleTree(MemNodeStore()), appendOneByOne = true)

    @Test
    fun merkleBatched() = checkTree(MerkleTree(MemNodeStore()), appendOneByOne = false)

    @Test
    fun merkleOnDisk() {
        val dir = Files.createTempDirectory("tree").toFile()
        val store = FileNodeStore(dir)
        checkTree(MerkleTree(store), appendOneByOne = false)
        // Reopened from disk, the tree is the same tree.
        val root = MerkleTree(store, 37).root()
        store.close()
        assertEquals(root, MerkleTree(FileNodeStore(dir), 37).root())
        assertFalse(root.isZero)
    }

    @Test
    fun hashThroughput() {
        val n = 2000
        val t0 = System.nanoTime()
        var x = Fr.ONE
        repeat(n) { x = Poseidon2.hash(x, x) }
        val us = (System.nanoTime() - t0) / 1000 / n
        println("poseidon2 arity-2: ${us}us/hash on the JVM")
    }
}
