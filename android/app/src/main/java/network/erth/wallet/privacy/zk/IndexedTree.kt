package network.erth.wallet.privacy.zk

/**
 * The stake nullifier tree as it stood at a snapshot (chain zk/indexed,
 * ORCHARD_DESIGN.md 15): an indexed (sorted) tree on the depth-32 Poseidon2
 * Merkle tree, leaf i = H(TAG_SNFL, value, next_value, next_index), leaf 0
 * the sentinel (value 0), each leaf pointing at the next larger value
 * (0, 0 for the largest).
 *
 * Built from the values in insertion order ([values][i] is leaf i + 1). An
 * insert only appends a leaf and repoints its predecessor, so the tree after
 * n inserts is fully determined by the final sorted order: every leaf points
 * at its value's successor among all n. The leaves are written once, in one
 * batch (about two hashes a leaf), not by replaying n inserts (64 hashes
 * each); [root] equals the chain's root after the same inserts.
 */
class IndexedTree private constructor(
    private val values: List<Fr>,
    /** Leaf indexes ordered by value (the sentinel 0 first). */
    private val sorted: IntArray,
    private val tree: MerkleTree,
) {
    /** The leaf count, sentinel included (the chain's nf_size; 1 when nothing was inserted). */
    val size: Long get() = tree.size

    fun root(): Fr = tree.root()

    /** Whether [v] was inserted. */
    fun contains(v: Fr): Boolean = search(v) >= 0

    /** Position of [v] in [sorted] (>= 0) or -(insertion point) - 1. */
    private fun search(v: Fr): Int {
        val key = v.toBigInteger()
        var lo = 0
        var hi = sorted.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = valueAt(sorted[mid]).toBigInteger().compareTo(key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    private fun valueAt(index: Int): Fr = if (index == 0) Fr.ZERO else values[index - 1]

    /** A non-membership witness: circuits/vote's low_value, low_next_value, low_next_index, low_index, low_path. */
    data class Witness(val lowValue: Fr, val lowNextValue: Fr, val lowNextIndex: Long, val lowIndex: Long, val lowPath: List<Fr>) {
        val leaf: Fr get() = Privacy.nfLeaf(lowValue, lowNextValue, lowNextIndex)

        /** As the circuit checks it (privacy_core::assert_not_in_indexed). */
        fun proves(v: Fr, root: Fr): Boolean {
            if (Merkle.rootFromPath(leaf, lowIndex, lowPath) != root) return false
            if (lowValue >= v) return false
            return lowNextValue.isZero || v < lowNextValue
        }
    }

    /** [v]'s low leaf and its path; null when [v] is in the tree (or is 0, the sentinel). */
    fun nonMembership(v: Fr): Witness? {
        if (v.isZero) return null
        val at = search(v)
        if (at >= 0) return null
        val ins = -(at + 1) // first sorted slot above v; the slot below holds the predecessor (the sentinel at least)
        val low = sorted[ins - 1]
        val (nv, ni) = if (ins < sorted.size) valueAt(sorted[ins]) to sorted[ins].toLong() else Fr.ZERO to 0L
        return Witness(valueAt(low), nv, ni, low.toLong(), tree.path(low.toLong()))
    }

    companion object {
        /** The root of a tree nothing was inserted in (chain indexed.EmptyRoot). */
        val EMPTY_ROOT: Fr by lazy { build(emptyList()).root() }

        /**
         * The tree after inserting [values] in order. Refuses what the chain
         * never inserts: 0 (the sentinel) or a repeated value.
         */
        fun build(values: List<Fr>): IndexedTree {
            require(values.size < Int.MAX_VALUE - 1)
            val ints = values.map { it.toBigInteger() }
            require(ints.none { it.signum() == 0 }) { "0 is the sentinel, never a stake nullifier" }
            // Leaf indexes 1..n by value; the sentinel (0) sorts first.
            val order = (1..values.size).sortedWith { a, b -> ints[a - 1].compareTo(ints[b - 1]) }
            for (k in 1 until order.size) require(ints[order[k - 1] - 1] != ints[order[k] - 1]) { "a stake nullifier repeats" }
            val sorted = IntArray(order.size + 1).also { s -> order.forEachIndexed { k, i -> s[k + 1] = i } }
            val next = IntArray(values.size + 1) // leaf index -> its successor's leaf index (0: none)
            for (k in 0 until sorted.size - 1) next[sorted[k]] = sorted[k + 1]
            val leaves = (0..values.size).map { i ->
                val n = next[i]
                val v = if (i == 0) Fr.ZERO else values[i - 1]
                if (n == 0) Privacy.nfLeaf(v, Fr.ZERO, 0) else Privacy.nfLeaf(v, values[n - 1], n.toLong())
            }
            val tree = MerkleTree(MemNodeStore())
            tree.appendAll(leaves)
            return IndexedTree(values, sorted, tree)
        }
    }
}
