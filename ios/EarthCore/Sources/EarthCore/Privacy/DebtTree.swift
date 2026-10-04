import BigInt
import Foundation

/// The slash debt tree (chain zk/debt, ORCHARD_DESIGN 20.6): an indexed
/// (sorted) tree on the depth-32 Poseidon2 Merkle tree with one row per
/// SLASHED redelegation, leaf i = H(TAG_DEBTL, key, next_key, next_index,
/// retained), leaf 0 the sentinel (0, smallest key, its index, 0). A move
/// with a row is worth its row's retained derth; a move absent (a low leaf
/// below it whose successor is above it, or none) is worth its whole exposure.
/// Ports `privacy/zk/DebtTree.kt`.
///
/// Built from the rows in insertion order, each with its latest retained
/// (Query/DebtTree, or the indexer's stream of the same rows): as for
/// `IndexedTree`, the leaves after n inserts are fixed by the final sorted
/// order, so they are written once. A tree of no row is the sentinel alone
/// (zk/debt EmptyRoot, also what the chain reports while its size is 0).
/// Immutable once built: safe to share.
public final class DebtTree: @unchecked Sendable {
    private let keys: [Fr]
    private let bigKeys: [BigUInt]
    private let retainedValues: [UInt64]
    /// Leaf indexes ordered by key (the sentinel 0 first).
    private let sorted: [Int]
    private let tree: MerkleTree

    public enum Error: Swift.Error, Equatable {
        case zero
        case repeated
        case tooLarge
    }

    /// The circuits' debt_low_* inputs for a move key: its own leaf when it
    /// has a row, else its low leaf (privacy_core::debt_retained).
    public struct Witness: Equatable, Sendable {
        public let lowKey: Fr
        public let lowNextKey: Fr
        public let lowNextIndex: UInt64
        public let lowRetained: UInt64
        public let lowIndex: UInt64
        public let lowPath: [Fr]

        public init(lowKey: Fr, lowNextKey: Fr, lowNextIndex: UInt64, lowRetained: UInt64, lowIndex: UInt64, lowPath: [Fr]) {
            self.lowKey = lowKey; self.lowNextKey = lowNextKey; self.lowNextIndex = lowNextIndex
            self.lowRetained = lowRetained; self.lowIndex = lowIndex; self.lowPath = lowPath
        }

        public var leaf: Fr { PrivacyHash.debtLeaf(key: lowKey, nextKey: lowNextKey, nextIndex: lowNextIndex, retained: lowRetained) }

        /// What `exposed` derth of move `key` is worth by this witness under `root`, as the circuit computes it; nil if it proves nothing.
        public func retained(key: Fr, exposed: UInt64, root: Fr) -> UInt64? {
            guard Merkle.rootFromPath(leaf: leaf, index: lowIndex, siblings: lowPath) == root else { return nil }
            if lowKey == key { return lowRetained <= exposed ? lowRetained : nil }
            guard lowKey < key else { return nil }
            if !lowNextKey.isZero && !(key < lowNextKey) { return nil }
            return exposed
        }

        /// An unused slot's witness (all zero: the circuit reads none).
        public static let none = Witness(lowKey: .zero, lowNextKey: .zero, lowNextIndex: 0, lowRetained: 0, lowIndex: 0,
                                         lowPath: [Fr](repeating: .zero, count: Merkle.depth))
    }

    /// zk/debt EmptyRoot: the sentinel alone.
    public static let emptyRoot: Fr = (try! DebtTree([])).root()

    /// The tree of `rows` (key, latest retained) in insertion order. Refuses
    /// what the chain never writes: key 0 (the sentinel's) or a repeated key,
    /// or a retained above 2^63-1.
    public init(_ rows: [(key: Fr, retained: UInt64)]) throws {
        guard UInt64(rows.count) < Merkle.capacity - 1 else { throw Error.tooLarge }
        let keys = rows.map(\.key)
        let big = keys.map(\.bigUInt)
        guard !big.contains(0) else { throw Error.zero }
        guard rows.allSatisfy({ $0.retained <= UInt64(Int64.max) }) else { throw Error.tooLarge }
        let order = (1 ..< rows.count + 1).sorted { big[$0 - 1] < big[$1 - 1] }
        for k in order.indices.dropFirst() where big[order[k - 1] - 1] == big[order[k] - 1] { throw Error.repeated }
        let sorted = [0] + order
        var next = [Int](repeating: 0, count: rows.count + 1)
        for k in 0 ..< sorted.count - 1 { next[sorted[k]] = sorted[k + 1] }
        let leaves = (0 ... rows.count).map { i -> Fr in
            let k = i == 0 ? Fr.zero : keys[i - 1]
            let r = i == 0 ? UInt64(0) : rows[i - 1].retained
            let n = next[i]
            return n == 0 ? PrivacyHash.debtLeaf(key: k, nextKey: .zero, nextIndex: 0, retained: r)
                : PrivacyHash.debtLeaf(key: k, nextKey: keys[n - 1], nextIndex: UInt64(n), retained: r)
        }
        let t = MerkleTree(store: MemNodeStore())
        t.appendAll(leaves)
        self.keys = keys; self.bigKeys = big; self.retainedValues = rows.map(\.retained); self.sorted = sorted; self.tree = t
    }

    public func root() -> Fr { tree.root() }

    /// The rows held (the chain's size less the sentinel).
    public var rows: Int { keys.count }

    private func bigKey(_ index: Int) -> BigUInt { index == 0 ? 0 : bigKeys[index - 1] }

    private func key(_ index: Int) -> Fr { index == 0 ? .zero : keys[index - 1] }

    private func search(_ k: Fr) -> (found: Bool, at: Int) {
        let b = k.bigUInt
        var lo = 0, hi = sorted.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let c = bigKey(sorted[mid])
            if c < b { lo = mid + 1 } else if c > b { hi = mid - 1 } else { return (true, mid) }
        }
        return (false, lo)
    }

    /// A move's row: what its exposure is still worth, nil if it was never slashed.
    public func retainedOf(_ key: Fr) -> UInt64? {
        let (found, at) = search(key)
        return found ? retainedValues[sorted[at] - 1] : nil
    }

    /// `key`'s witness (nil for 0, the sentinel's key).
    public func witness(_ key: Fr) -> Witness? {
        guard !key.isZero else { return nil }
        let (found, at) = search(key)
        // Its own slot, or the one below its insertion point (the sentinel at least).
        let slot = found ? at : at - 1
        let leafIndex = sorted[slot]
        let (nk, ni): (Fr, UInt64) = slot + 1 < sorted.count ? (self.key(sorted[slot + 1]), UInt64(sorted[slot + 1])) : (.zero, 0)
        let r = leafIndex == 0 ? UInt64(0) : retainedValues[leafIndex - 1]
        return Witness(lowKey: self.key(leafIndex), lowNextKey: nk, lowNextIndex: ni, lowRetained: r, lowIndex: UInt64(leafIndex),
                       lowPath: tree.path(UInt64(leafIndex)))
    }
}
