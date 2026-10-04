package network.erth.wallet.privacy.zk

/**
 * The slash debt tree (chain zk/debt, ORCHARD_DESIGN 20.6): an indexed
 * (sorted) tree on the depth-32 Poseidon2 Merkle tree with one row per
 * SLASHED redelegation, leaf i = H(TAG_DEBTL, key, next_key, next_index,
 * retained), leaf 0 the sentinel (0, smallest key, its index, 0). A move
 * with a row is worth its row's retained derth; a move absent (a low leaf
 * below it whose successor is above it, or none) is worth its whole exposure.
 *
 * Built from the rows in insertion order, each with its latest retained
 * (Query/DebtTree, or the indexer's stream of the same rows): as for
 * [IndexedTree], the leaves after n inserts are fixed by the final sorted
 * order, so they are written once. A tree of no row is the sentinel alone
 * (zk/debt EmptyRoot, also what the chain reports while its size is 0).
 */
class DebtTree private constructor(
    private val keys: List<Fr>,
    private val retained: List<Long>,
    /** Leaf indexes ordered by key (the sentinel 0 first). */
    private val sorted: IntArray,
    private val tree: MerkleTree,
) {
    fun root(): Fr = tree.root()

    /** The rows held (the chain's size less the sentinel). */
    val rows: Int get() = keys.size

    /** A move's row: what its exposure is still worth, null if it was never slashed. */
    fun retainedOf(key: Fr): Long? = search(key).takeIf { it >= 0 }?.let { retained[sorted[it] - 1] }

    private fun keyAt(index: Int): Fr = if (index == 0) Fr.ZERO else keys[index - 1]

    private fun search(k: Fr): Int {
        val key = k.toBigInteger()
        var lo = 0
        var hi = sorted.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = keyAt(sorted[mid]).toBigInteger().compareTo(key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    /**
     * The circuits' debt_low_* inputs for a move key: its own leaf when it has
     * a row, else its low leaf (privacy_core::debt_retained).
     */
    data class Witness(val lowKey: Fr, val lowNextKey: Fr, val lowNextIndex: Long, val lowRetained: Long, val lowIndex: Long, val lowPath: List<Fr>) {
        val leaf: Fr get() = Privacy.debtLeaf(lowKey, lowNextKey, lowNextIndex, lowRetained)

        /** What [exposed] derth of move [key] is worth by this witness under [root], as the circuit computes it; null if it proves nothing. */
        fun retained(key: Fr, exposed: Long, root: Fr): Long? {
            if (Merkle.rootFromPath(leaf, lowIndex, lowPath) != root) return null
            if (lowKey == key) return lowRetained.takeIf { it in 0..exposed }
            if (lowKey >= key) return null
            if (!lowNextKey.isZero && key >= lowNextKey) return null
            return exposed
        }

        companion object {
            /** An unused slot's witness (all zero: the circuit reads none). */
            val NONE = Witness(Fr.ZERO, Fr.ZERO, 0, 0, 0, List(Merkle.DEPTH) { Fr.ZERO })
        }
    }

    /** [key]'s witness against [root] (null for 0, the sentinel's key). */
    fun witness(key: Fr): Witness? {
        if (key.isZero) return null
        val at = search(key)
        // Its own slot, or the one below its insertion point (the sentinel at least).
        val slot = if (at >= 0) at else -(at + 1) - 1
        val leafIndex = sorted[slot]
        val (nk, ni) = if (slot + 1 < sorted.size) keyAt(sorted[slot + 1]) to sorted[slot + 1].toLong() else Fr.ZERO to 0L
        val r = if (leafIndex == 0) 0L else retained[leafIndex - 1]
        return Witness(keyAt(leafIndex), nk, ni, r, leafIndex.toLong(), tree.path(leafIndex.toLong()))
    }

    companion object {
        /** zk/debt EmptyRoot: the sentinel alone. */
        val EMPTY_ROOT: Fr by lazy { build(emptyList()).root() }

        /**
         * The tree of [rows] (key, latest retained) in insertion order.
         * Refuses what the chain never writes: key 0 (the sentinel's) or a
         * repeated key, or a retained above 2^63-1.
         */
        fun build(rows: List<Pair<Fr, Long>>): DebtTree {
            require(rows.size < Merkle.CAPACITY - 1 && rows.size < Int.MAX_VALUE - 1)
            val keys = rows.map { it.first }
            val ints = keys.map { it.toBigInteger() }
            require(ints.none { it.signum() == 0 }) { "0 is the sentinel, never a move key" }
            require(rows.all { it.second >= 0 }) { "a retained above 2^63-1" }
            val order = (1..keys.size).sortedWith { a, b -> ints[a - 1].compareTo(ints[b - 1]) }
            for (k in 1 until order.size) require(ints[order[k - 1] - 1] != ints[order[k] - 1]) { "a debt row repeats" }
            val sorted = IntArray(order.size + 1).also { s -> order.forEachIndexed { k, i -> s[k + 1] = i } }
            val next = IntArray(keys.size + 1)
            for (k in 0 until sorted.size - 1) next[sorted[k]] = sorted[k + 1]
            val leaves = (0..keys.size).map { i ->
                val n = next[i]
                val k = if (i == 0) Fr.ZERO else keys[i - 1]
                val r = if (i == 0) 0L else rows[i - 1].second
                if (n == 0) Privacy.debtLeaf(k, Fr.ZERO, 0, r) else Privacy.debtLeaf(k, keys[n - 1], n.toLong(), r)
            }
            val tree = MerkleTree(MemNodeStore())
            tree.appendAll(leaves)
            return DebtTree(keys, rows.map { it.second }, sorted, tree)
        }
    }
}
