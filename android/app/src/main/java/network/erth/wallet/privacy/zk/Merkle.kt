package network.erth.wallet.privacy.zk

import java.io.File
import java.io.RandomAccessFile

/**
 * The depth-32 binary Poseidon2 tree of the chain's zk/merkle, shared by the
 * identity tree (updatable: leaves are zeroed) and the note tree
 * (append-only):
 *
 *     node = Poseidon2([left, right])    empty leaf = 0
 *     zero[i+1] = H(zero[i], zero[i])
 *     bit i of the index = 1  <=>  the running node is the right child at level i
 *
 * The wallet rebuilds both trees from the indexer's full streams and takes
 * its own paths locally, so nothing it asks for names a leaf of its own.
 */
object Merkle {
    const val DEPTH = 32
    const val CAPACITY = 1L shl DEPTH

    val ZERO: List<Fr> = ArrayList<Fr>(DEPTH + 1).apply {
        add(Fr.ZERO)
        for (i in 0 until DEPTH) add(node(this[i], this[i]))
    }

    fun node(l: Fr, r: Fr): Fr = Poseidon2.hash(l, r)

    /** Recomputes a root the way the circuit does (privacy_core::merkle_root). */
    fun rootFromPath(leaf: Fr, index: Long, siblings: List<Fr>): Fr {
        require(siblings.size == DEPTH)
        var cur = leaf
        for (lvl in 0 until DEPTH) {
            cur = if ((index ushr lvl) and 1L == 0L) node(cur, siblings[lvl]) else node(siblings[lvl], cur)
        }
        return cur
    }
}

/** Non-empty tree nodes; level 0 is leaves, level 32 the root. A node never written reads as null. */
interface NodeStore {
    fun get(level: Int, index: Long): Fr?
    fun set(level: Int, index: Long, v: Fr)
    /** Makes every set so far durable; a no-op for memory. */
    fun flush() {}
    /** Drops every node. */
    fun clear()
}

class MemNodeStore : NodeStore {
    private val m = HashMap<Long, Fr>()
    private fun key(level: Int, index: Long) = (level.toLong() shl 40) or index
    override fun get(level: Int, index: Long): Fr? = m[key(level, index)]
    override fun set(level: Int, index: Long, v: Fr) { m[key(level, index)] = v }
    override fun clear() = m.clear()
}

/**
 * One file per level, node i at byte offset 32*i. Nothing above level 0 can
 * hash to 0 without a Poseidon2 preimage, so 32 zero bytes there (a hole, or
 * past the end) read as unwritten; at level 0 they read as the empty leaf,
 * which is what a zeroed identity leaf is.
 */
class FileNodeStore(private val dir: File) : NodeStore {
    private val files = arrayOfNulls<RandomAccessFile>(Merkle.DEPTH + 1)

    init { dir.mkdirs() }

    private fun f(level: Int): RandomAccessFile =
        files[level] ?: RandomAccessFile(File(dir, "level$level"), "rw").also { files[level] = it }

    override fun get(level: Int, index: Long): Fr? {
        val raf = f(level)
        val off = index * 32
        if (off + 32 > raf.length()) return null
        val b = ByteArray(32)
        raf.seek(off)
        raf.readFully(b)
        if (b.all { it.toInt() == 0 }) return if (level == 0) Fr.ZERO else null
        return Fr.fromBytes(b)
    }

    override fun set(level: Int, index: Long, v: Fr) {
        val raf = f(level)
        raf.seek(index * 32)
        raf.write(v.toBytes())
    }

    override fun flush() { files.forEach { it?.fd?.sync() } }

    fun close() { files.forEach { it?.close() }; files.fill(null) }

    /** Drops everything (a resync from scratch). */
    override fun clear() {
        close()
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** An incremental sparse Merkle tree over a [NodeStore]; [size] is the append cursor. */
class MerkleTree(private val store: NodeStore, size: Long = 0) {
    var size: Long = size
        private set

    private fun node(level: Int, index: Long): Fr = store.get(level, index) ?: Merkle.ZERO[level]

    fun root(): Fr = node(Merkle.DEPTH, 0)

    fun leaf(index: Long): Fr = node(0, index)

    fun append(leaf: Fr): Long {
        appendAll(listOf(leaf))
        return size - 1
    }

    /**
     * Appends [leaves] and rehashes each level's changed range once: about two
     * hashes a leaf for a large batch, where appending one at a time costs 32.
     */
    fun appendAll(leaves: List<Fr>) {
        if (leaves.isEmpty()) return
        require(size + leaves.size <= Merkle.CAPACITY) { "tree full" }
        val start = size
        leaves.forEachIndexed { i, l -> store.set(0, start + i, l) }
        size += leaves.size
        rehash(start, size - 1)
    }

    /** Overwrites an appended leaf (the identity tree zeroes with Fr.ZERO). */
    fun update(index: Long, leaf: Fr) {
        require(index in 0 until size) { "leaf $index not yet appended" }
        store.set(0, index, leaf)
        rehash(index, index)
    }

    /** Several updates, rehashing each level's touched nodes once. */
    fun updateAll(updates: Map<Long, Fr>) {
        if (updates.isEmpty()) return
        updates.forEach { (i, l) -> require(i in 0 until size); store.set(0, i, l) }
        var dirty = updates.keys.toSortedSet()
        for (lvl in 0 until Merkle.DEPTH) {
            val parents = dirty.mapTo(sortedSetOf()) { it ushr 1 }
            for (p in parents) store.set(lvl + 1, p, Merkle.node(node(lvl, 2 * p), node(lvl, 2 * p + 1)))
            dirty = parents
        }
    }

    private fun rehash(lo0: Long, hi0: Long) {
        var lo = lo0
        var hi = hi0
        for (lvl in 0 until Merkle.DEPTH) {
            val plo = lo ushr 1
            val phi = hi ushr 1
            for (p in plo..phi) store.set(lvl + 1, p, Merkle.node(node(lvl, 2 * p), node(lvl, 2 * p + 1)))
            lo = plo
            hi = phi
        }
    }

    /** The 32 siblings from the leaf level up. */
    fun path(index: Long): List<Fr> {
        require(index in 0 until size) { "leaf $index not yet appended" }
        val out = ArrayList<Fr>(Merkle.DEPTH)
        var i = index
        for (lvl in 0 until Merkle.DEPTH) {
            out.add(node(lvl, i xor 1L))
            i = i ushr 1
        }
        return out
    }

    /**
     * The node at ([level], [index]) as it stood when the tree held [size]
     * leaves. Append-only trees only: a subtree wholly below [size] has not
     * changed since, one wholly above was empty, and only the nodes on the
     * frontier between are rehashed. Used to prove against an older recorded
     * root (a stake vote's proposal snapshot).
     */
    private fun nodeAt(level: Int, index: Long, size: Long): Fr {
        val start = index shl level
        val end = (index + 1) shl level
        if (end <= size) return node(level, index)
        if (start >= size) return Merkle.ZERO[level]
        return Merkle.node(nodeAt(level - 1, 2 * index, size), nodeAt(level - 1, 2 * index + 1, size))
    }

    /** The root when the tree held [size] leaves (append-only trees). */
    fun rootAt(size: Long): Fr {
        require(size in 0..this.size)
        return nodeAt(Merkle.DEPTH, 0, size)
    }

    /** [index]'s path against [rootAt] ([size]). */
    fun pathAt(index: Long, size: Long): List<Fr> {
        require(size in 0..this.size && index in 0 until size) { "leaf $index is not in the first $size" }
        val out = ArrayList<Fr>(Merkle.DEPTH)
        var i = index
        for (lvl in 0 until Merkle.DEPTH) {
            out.add(nodeAt(lvl, i xor 1L, size))
            i = i ushr 1
        }
        return out
    }

    fun flush() = store.flush()

    /** Empties the tree (a resync from scratch). */
    fun clear() {
        store.clear()
        size = 0
    }
}
