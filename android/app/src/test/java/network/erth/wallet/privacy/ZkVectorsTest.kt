package network.erth.wallet.privacy

import network.erth.wallet.privacy.Vectors.fe
import network.erth.wallet.privacy.Vectors.fr
import network.erth.wallet.privacy.Vectors.json
import network.erth.wallet.privacy.Vectors.unhex
import network.erth.wallet.privacy.zk.Fr
import network.erth.wallet.privacy.zk.IndexedTree
import network.erth.wallet.privacy.zk.MemNodeStore
import network.erth.wallet.privacy.zk.Merkle
import network.erth.wallet.privacy.zk.MerkleTree
import network.erth.wallet.privacy.zk.FileNodeStore
import network.erth.wallet.privacy.zk.Poseidon2
import network.erth.wallet.privacy.zk.Privacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            "asset" to Privacy.TAG_ASSET, "signal" to Privacy.TAG_SIGNAL, "bytes" to Privacy.TAG_BYTES, "scope" to Privacy.TAG_SCOPE, "affiliate" to Privacy.TAG_AFFILIATE,
            "stake" to Privacy.TAG_STAKE, "spc" to Privacy.TAG_SPC, "snf" to Privacy.TAG_SNF, "otag" to Privacy.TAG_OTAG,
            "snfl" to Privacy.TAG_SNFL, "vnf" to Privacy.TAG_VNF,
            "gen" to network.erth.wallet.privacy.zk.Grumpkin.TAG_GEN, "cv_r" to network.erth.wallet.privacy.zk.Grumpkin.TAG_CV_R,
            "bsig" to network.erth.wallet.privacy.zk.Grumpkin.TAG_BSIG, "bundle" to network.erth.wallet.privacy.tx.PrivateMsgs.TAG_BUNDLE,
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
        assertEquals(d.getString("leaf"), Privacy.identityLeaf(idc, fr(d.getString("leaf_dsc")), Privacy.countryField("DE"), 1_790_000_000, 0).toHex())
        // A switched or re-entered identity's leaf commits to predecessor_at.
        assertEquals(d.getString("leaf_pred"), Privacy.identityLeaf(idc, fr(d.getString("leaf_dsc")), Privacy.countryField("DE"), 1_790_000_000, 1_790_000_000).toHex())
        assertEquals(d.getString("sn"), Privacy.scopeNullifier(idSecret, Privacy.claimScope(20360)).toHex())
        val pc = Privacy.pc(opk, rho, rcm)
        assertEquals(d.getString("pc"), pc.toHex())
        assertEquals(d.getString("cm"), Privacy.cm(Privacy.assetId("uanml"), 1_000_000, pc).toHex())
        assertEquals(d.getString("nf"), Privacy.nf(nk, rho, 4_000_000_000).toHex())
        assertEquals(d.getString("reg_none"), Privacy.registrationBinding(idc, fe(1), "anml".toByteArray(), fe(2), "erth".toByteArray(), Fr.ZERO).toHex())
        // The chain's pinned vector (zk/privacy TestRegistrationBindingPinned).
        assertEquals("20ce5fccf5e6e20a8a7b80f7565e41a7c73dbb16ac5e53746e7234ba8b305b0c", d.getString("reg_pinned"))
        assertEquals(d.getString("reg_pinned"),
            Privacy.registrationBinding(Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO).toHex())
        // The stake tree.
        val spc = Privacy.stakePc(opk, rho, rcm)
        assertEquals(d.getString("spc"), spc.toHex())
        assertEquals(d.getString("stake_cm"), Privacy.stakeCm(Privacy.assetId("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"), 1_800_000, spc).toHex())
        assertEquals(d.getString("stake_nf"), Privacy.stakeNf(nk, rho, 4_000_000_000).toHex())
        assertEquals(fe(1006), fr(d.getString("otag_salt")))
        assertEquals(d.getString("otag"), Privacy.ownerTag(opk, fe(1006)).toHex())
        // Stake votes (ORCHARD_DESIGN 15), and the design's golden values (= Noir test_go_parity).
        assertEquals(d.getString("nf_leaf_1_2_3"), Privacy.nfLeaf(Fr.of(1), Fr.of(2), 3).toHex())
        assertEquals("0cdc3a81748c6389efaa3a6c29b7f4609a8e9f860230b70413e8bef512978276", d.getString("nf_leaf_1_2_3"))
        assertEquals(d.getString("nf_leaf"), Privacy.nfLeaf(fe(1007), fe(1008), 4_000_000_000).toHex())
        assertEquals(d.getString("vote_nf"), Privacy.voteNf(nk, rho, 4_000_000_000, 5).toHex())
        assertEquals(d.getString("vote_nf_5eed"), Privacy.voteNf(Fr.of(0x5eed), Fr.of(0xa1), 1, 7).toHex())
        assertEquals("1ada84dad3e6afde3f370e97edf4df2ee4eeb6b1400d5c5f41882552f578ba2f", d.getString("vote_nf_5eed"))
    }

    /** The stake nullifier indexed tree against zk/indexed: roots by insert count, non-membership witnesses. */
    @Test
    fun indexedTree() {
        val ix = json.getJSONObject("indexed")
        assertEquals(ix.getString("empty_root"), IndexedTree.EMPTY_ROOT.toHex())
        assertEquals("18f5a2d2d3273f584793e90ac9bf77abf0ff2a05a101cd5943eaf7bbd0bd5b10", ix.getString("empty_root"))
        val vs = ix.getJSONArray("values").let { a -> (0 until a.length()).map { fr(a.getString(it)) } }
        val roots = ix.getJSONObject("roots")
        for (k in roots.keys()) assertEquals(k, roots.getString(k), IndexedTree.build(vs.take(k.toInt())).root().toHex())
        val t = IndexedTree.build(vs)
        assertTrue(vs.all { t.contains(it) && t.nonMembership(it) == null })
        assertNull(t.nonMembership(Fr.ZERO))
        val ws = ix.getJSONArray("witnesses")
        for (i in 0 until ws.length()) {
            val w = ws.getJSONObject(i)
            val v = fr(w.getString("value"))
            val mine = t.nonMembership(v)!!
            assertEquals(w.getString("low_value"), mine.lowValue.toHex())
            assertEquals(w.getString("low_next_value"), mine.lowNextValue.toHex())
            assertEquals(w.getLong("low_next_index"), mine.lowNextIndex)
            assertEquals(w.getLong("low_index"), mine.lowIndex)
            val p = w.getJSONArray("low_path")
            assertEquals((0 until p.length()).map { p.getString(it) }, mine.lowPath.map { it.toHex() })
            assertTrue(mine.proves(v, t.root()))
        }
        // The chain refuses what it never inserts.
        assertThrows(IllegalArgumentException::class.java) { IndexedTree.build(listOf(vs[0], vs[0])) }
        assertThrows(IllegalArgumentException::class.java) { IndexedTree.build(listOf(Fr.ZERO)) }
    }

    @Test
    fun scopes() {
        val s = json.getJSONObject("scopes")
        assertEquals(s.getString("claim_20360"), Privacy.claimScope(20360).toHex())
        assertEquals(s.getString("caretaker"), Privacy.caretakerScope().toHex())
        assertEquals(s.getString("handle"), Privacy.handleScope().toHex())
        assertEquals(s.getString("proposal_5_0"), Privacy.proposalScope(5, 0).toHex())
        assertEquals(s.getString("proposal_5_1"), Privacy.proposalScope(5, 1).toHex())
        assertEquals(s.getString("removal_3"), Privacy.removalScope(3).toHex())
        assertEquals(s.getString("propose_removal_2_100"), Privacy.proposeRemovalScope(2, 100).toHex())
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
