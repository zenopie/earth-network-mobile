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

    /**
     * The referral note's opening the chain derives
     * (zk/privacy.ReferralOpening) and the pc / cm a handle owner's wallet
     * recomputes from the mint row (5 ERTH to OwnerPK(7100+i)).
     */
    @Test
    fun referralOpening() {
        val a = json.getJSONArray("referral_opening")
        assertEquals(5, a.length())
        for (i in 0 until a.length()) {
            val v = a.getJSONObject(i)
            val (rho, rcm) = Privacy.referralOpening(fr(v.getString("nullifier")), v.getLong("leaf_index"))
            assertEquals("rho $i", v.getString("rho"), rho.toHex())
            assertEquals("rcm $i", v.getString("rcm"), rcm.toHex())
            val owner = fr(v.getString("owner_pk"))
            assertEquals("pc $i", v.getString("pc"), Privacy.pc(owner, rho, rcm).toHex())
            assertEquals("cm $i", v.getString("cm"), Privacy.cm(Privacy.assetId("uerth"), 5_000_000, Privacy.pc(owner, rho, rcm)).toHex())
        }
    }

    @Test
    fun tagsAssetsBytesCountry() {
        val tags = json.getJSONObject("tags")
        val mine = mapOf(
            "id" to Privacy.TAG_ID, "owner" to Privacy.TAG_OWNER, "leaf" to Privacy.TAG_LEAF, "sn" to Privacy.TAG_SN,
            "pc" to Privacy.TAG_PC, "cm" to Privacy.TAG_CM, "nf" to Privacy.TAG_NF, "reg" to Privacy.TAG_REG,
            "asset" to Privacy.TAG_ASSET, "signal" to Privacy.TAG_SIGNAL, "bytes" to Privacy.TAG_BYTES, "scope" to Privacy.TAG_SCOPE, "affiliate" to Privacy.TAG_AFFILIATE, "referral" to Privacy.TAG_REFERRAL,
            "stake" to Privacy.TAG_STAKE, "spc" to Privacy.TAG_SPC, "snf" to Privacy.TAG_SNF, "otag" to Privacy.TAG_OTAG,
            "snfl" to Privacy.TAG_SNFL, "vnf" to Privacy.TAG_VNF, "slabel" to Privacy.TAG_SLABEL, "debtl" to Privacy.TAG_DEBTL,
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
        assertEquals(d.getString("reg_none"), Privacy.registrationBinding("earth-1", idc, fe(1), "anml".toByteArray(), fe(2), "erth".toByteArray(), Fr.ZERO).toHex())
        // The chain's pinned vector (zk/privacy TestRegistrationBindingPinned, with the chain id).
        assertEquals("148b3513a501b6ff9c02314f355cb83fb544e22b2a9df79552fe49c944424159", d.getString("reg_pinned"))
        assertEquals(d.getString("reg_pinned"),
            Privacy.registrationBinding("earth-1", Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO).toHex())
        // Another network's binding differs: a registration does not replay across chains.
        assertEquals(d.getString("reg_testnet"),
            Privacy.registrationBinding("earth-testnet-1", Fr.of(1), Fr.of(2), "anml".toByteArray(), Fr.of(3), "erth".toByteArray(), Fr.ZERO).toHex())
        // The stake tree.
        val spc = Privacy.stakePc(opk, rho, rcm)
        assertEquals(d.getString("spc"), spc.toHex())
        val derthAsset = Privacy.assetId("derth/earthvaloper1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq")
        assertEquals(d.getString("stake_cm"), Privacy.stakeCm(derthAsset, 1_800_000, spc, Fr.ZERO).toHex())
        // Slash labels and the debt tree's leaves, and zk/debt TestNoirParity's pins (= Noir test_go_parity_debt).
        val label = Privacy.stakeLabel(fe(1009), 1_790_000_000, 400_000)
        assertEquals(d.getString("stake_label"), label.toHex())
        assertEquals(d.getString("stake_cm_labelled"), Privacy.stakeCm(derthAsset, 1_800_000, spc, label).toHex())
        assertEquals(d.getString("debt_leaf"), Privacy.debtLeaf(fe(1010), fe(1011), 4_000_000_000, 123_456).toHex())
        assertEquals(d.getString("debt_leaf_1_2_3_4"), Privacy.debtLeaf(Fr.of(1), Fr.of(2), 3, 4).toHex())
        assertEquals("0b28cc858d976ddad0ede75ca9538f9b5ab36538964f6e241b8e89be2711e82a", d.getString("debt_leaf_1_2_3_4"))
        assertEquals("2dfbc154973d1d3ec6e03137ba41b5c2cf5119f66cc69e3e58c033a77c80a881", Privacy.stakeLabel(Fr.of(0x4d4b), 1000, 200).toHex())
        assertEquals(d.getString("stake_label_4d4b"), Privacy.stakeLabel(Fr.of(0x4d4b), 1000, 200).toHex())
        assertEquals("0ffc538b4162732774bd5026e7a07bd00d2fe406af55231fa0c255c321ad4232", Privacy.stakeCm(Fr.of(1), 2, Fr.of(3), Fr.of(4)).toHex())
        assertEquals(d.getString("stake_cm_1_2_3_4"), Privacy.stakeCm(Fr.of(1), 2, Fr.of(3), Fr.of(4)).toHex())
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
        assertEquals(d.getString("vote_pad_nf"), Privacy.votePadNf(nk, rho, 5).toHex())
        assertEquals(d.getString("vote_pad_nf_5eed"), Privacy.votePadNf(Fr.of(0x5eed), Fr.of(0x77), 7).toHex())
        assertEquals("08d195db55c5c0006ae0d2e8ee33df8cd5286226124074ad37c1d5ebe74b6235", d.getString("vote_pad_nf_5eed"))
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

    /** The slash debt tree against zk/debt: roots by write (a row rewritten in place), witnesses for rows and absent moves. */
    @Test
    fun debtTree() {
        val dj = json.getJSONObject("debt")
        assertEquals(dj.getString("empty_root"), network.erth.wallet.privacy.zk.DebtTree.EMPTY_ROOT.toHex())
        assertEquals("0cea3d3e26cd2710109d7cbff5bf48570ba54332f812d538893f0958007f6903", dj.getString("empty_root"))
        val rows = dj.getJSONArray("rows").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r -> fr(r.getString("key")) to r.getLong("retained") } } }
        val t = network.erth.wallet.privacy.zk.DebtTree.build(rows)
        assertEquals(dj.getString("root"), t.root().toHex())
        // Every write's root: the rows inserted so far, each at its latest retained then.
        val writes = (0 until 9).map { fe(9000L + it) to 1_000L * (it + 1) } + listOf(fe(9002) to 7L)
        val roots = dj.getJSONObject("roots_by_write")
        for (k in roots.keys()) {
            val n = k.toInt()
            val upTo = LinkedHashMap<Fr, Long>()
            writes.take(n).forEach { (key, r) -> upTo[key] = r }
            assertEquals("root after $n writes", roots.getString(k), network.erth.wallet.privacy.zk.DebtTree.build(upTo.entries.map { it.key to it.value }).root().toHex())
        }
        val ws = dj.getJSONArray("witnesses")
        for (i in 0 until ws.length()) {
            val w = ws.getJSONObject(i)
            val key = fr(w.getString("key"))
            val mine = t.witness(key)!!
            assertEquals(w.getString("low_key"), mine.lowKey.toHex())
            assertEquals(w.getString("low_next_key"), mine.lowNextKey.toHex())
            assertEquals(w.getLong("low_next_index"), mine.lowNextIndex)
            assertEquals(w.getLong("low_retained"), mine.lowRetained)
            assertEquals(w.getLong("low_index"), mine.lowIndex)
            val p = w.getJSONArray("low_path")
            assertEquals((0 until p.length()).map { p.getString(it) }, mine.lowPath.map { it.toHex() })
            assertEquals(w.getLong("retained"), mine.retained(key, w.getLong("exposed"), t.root()))
        }
        // A row's own key read through its low leaf, or against another root, proves nothing.
        val slashed = fe(9002)
        assertEquals(7L, t.retainedOf(slashed))
        assertNull(t.witness(Fr.of(1))!!.retained(slashed, 50_000, t.root()))
        assertNull(t.witness(slashed)!!.retained(slashed, 50_000, network.erth.wallet.privacy.zk.DebtTree.EMPTY_ROOT))
        assertThrows(IllegalArgumentException::class.java) { network.erth.wallet.privacy.zk.DebtTree.build(listOf(slashed to 1L, slashed to 2L)) }
        assertThrows(IllegalArgumentException::class.java) { network.erth.wallet.privacy.zk.DebtTree.build(listOf(Fr.ZERO to 1L)) }
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

    /**
     * 40 values inserted in a non-monotonic order by the chain's zk/indexed
     * Insert give the root the wallet's batch build does.
     */
    @Test
    fun indexedTreeRandomOrderMatchesChain() {
        val values = listOf(
            "0000000000000000000000000000000041a027f7785144a55afc45053c7f6819",
            "000000000000000000000000000000000e74f84711b59f587c9c1cad13ef5f24",
            "00000000000000000000000000000000162331bf65d3f2322ba8d991b6ee5024",
            "000000000000000000000000000000002f4f06b98574ded50d2ab81ec4774790",
            "0000000000000000000000000000000020b76d1f02eb1c11d68153f3947cec90",
            "00000000000000000000000000000000785ff57fe5a5217e61e2bad7778919e1",
            "000000000000000000000000000000005d9076f293f65d8143fcd0fd4c2e2f11",
            "000000000000000000000000000000007a2b38172940e200cecf9aff080966a4",
            "000000000000000000000000000000001ef049ca638d87936fc093b0a6030ba9",
            "0000000000000000000000000000000052b8ff4ebf53d5e76f2a209ff350c584",
            "0000000000000000000000000000000006465c2f9e05da1709157c2bd10d4fe9",
            "000000000000000000000000000000002c7855dc3608bdc4935514ef1bb8e139",
            "0000000000000000000000000000000004357e97fabe65184baac1abebd69b39",
            "0000000000000000000000000000000002d8bbe518b172eed7cb954c2b88d044",
            "00000000000000000000000000000000357eed4f7fbb3185aa23414cf6d9b171",
            "00000000000000000000000000000000053fbb00b4ecd04d4137a1ee380771b1",
            "0000000000000000000000000000000024a8473c44be89f91f5059c9579a5040",
            "00000000000000000000000000000000095b37c17798de8b140d01f830394ba9",
            "000000000000000000000000000000001de1b43699d6a00a48127ae035efde11",
            "000000000000000000000000000000003f0d619b304ae65c847be589f24fed21",
            "000000000000000000000000000000000151374cbbc54fa4b844ed100af63199",
            "0000000000000000000000000000000085dd1622c606bac6f477b5635da5aad1",
            "0000000000000000000000000000000001463d903b7df8c388cd319293b1bf64",
            "000000000000000000000000000000003a913350dcde598f2d3bcaea3e79bb44",
            "000000000000000000000000000000000272e77861f6426e6e0aa885142372b9",
            "0000000000000000000000000000000000f3d73053fedbe27ba9328854638e10",
            "000000000000000000000000000000006deed5b02eb9dd0fa0f153a316afde99",
            "00000000000000000000000000000000061c5c8cd37c721240e0267fe6e1ff31",
            "00000000000000000000000000000000230b475f441aff4a08cd5cc9eaa16919",
            "000000000000000000000000000000003bd136463b517a0c26d7e648454cb639",
            "00000000000000000000000000000000031127cf8a83168d011f3db6247d0281",
            "000000000000000000000000000000003e7821f58d76f39d6a5c48f4c2dc34a9",
            "00000000000000000000000000000000537a729d759281172d9c0e487a7da064",
            "00000000000000000000000000000000839fa3b92e3ee21284f6e39bcb0b7c90",
            "000000000000000000000000000000004b2fc9fe6e1e0bdf8b2239c288359a99",
            "0000000000000000000000000000000019e0fad5d4d9973563670ef60b7f5504",
            "00000000000000000000000000000000679e1b9056780fa53c1102de2e208924",
            "000000000000000000000000000000005971378e2fea038626401fe3f95a2b01",
            "00000000000000000000000000000000387ec0ee76642409ce9acf571e276059",
            "00000000000000000000000000000000552fbca30e05fa2e02fc57a6d7db8400",
        )
        assertEquals("2e98e4e0f9c6b5fa34290a608292d4622e2447ac7e42139deb35a53eaf75976b", IndexedTree.build(values.map { Fr.fromHex(it) }).root().toHex())
    }
}
