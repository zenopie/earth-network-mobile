import BigInt
import Foundation

/// The stake nullifier tree as it stood at a snapshot (chain zk/indexed,
/// ORCHARD_DESIGN.md 15): an indexed (sorted) tree on the depth-32 Poseidon2
/// Merkle tree, leaf i = H(TAG_SNFL, value, next_value, next_index), leaf 0
/// the sentinel (value 0), each leaf pointing at the next larger value
/// (0, 0 for the largest). Ports `privacy/zk/IndexedTree.kt`.
///
/// Built from the values in insertion order (`values[i]` is leaf i + 1). An
/// insert only appends a leaf and repoints its predecessor, so the tree after
/// n inserts is fully determined by the final sorted order: every leaf points
/// at its value's successor among all n. The leaves are written once, in one
/// batch (about two hashes a leaf), not by replaying n inserts (64 hashes
/// each); `root()` equals the chain's root after the same inserts.
public final class IndexedTree {
    private let values: [Fr]
    private let keys: [BigUInt]
    /// Leaf indexes ordered by value (the sentinel 0 first).
    private let sorted: [Int]
    private let tree: MerkleTree

    public enum Error: Swift.Error, Equatable {
        case zero
        case repeated
    }

    /// A non-membership witness: circuits/vote's low_value, low_next_value, low_next_index, low_index, low_path.
    public struct Witness: Equatable, Sendable {
        public let lowValue: Fr
        public let lowNextValue: Fr
        public let lowNextIndex: UInt64
        public let lowIndex: UInt64
        public let lowPath: [Fr]

        public var leaf: Fr { PrivacyHash.nfLeaf(value: lowValue, nextValue: lowNextValue, nextIndex: lowNextIndex) }

        /// As the circuit checks it (privacy_core::assert_not_in_indexed).
        public func proves(_ v: Fr, root: Fr) -> Bool {
            guard Merkle.rootFromPath(leaf: leaf, index: lowIndex, siblings: lowPath) == root else { return false }
            guard lowValue < v else { return false }
            return lowNextValue.isZero || v < lowNextValue
        }
    }

    /// The root of a tree nothing was inserted in (chain indexed.EmptyRoot).
    public static let emptyRoot: Fr = (try! IndexedTree([])).root()

    /// The tree after inserting `values` in order. Refuses what the chain
    /// never inserts: 0 (the sentinel) or a repeated value.
    public init(_ values: [Fr]) throws {
        let keys = values.map(\.bigUInt)
        guard !keys.contains(0) else { throw Error.zero }
        let order = (1 ..< values.count + 1).sorted { keys[$0 - 1] < keys[$1 - 1] }
        for k in order.indices.dropFirst() where keys[order[k - 1] - 1] == keys[order[k] - 1] { throw Error.repeated }
        let sorted = [0] + order
        var next = [Int](repeating: 0, count: values.count + 1) // leaf index -> its successor's leaf index (0: none)
        for k in 0 ..< sorted.count - 1 { next[sorted[k]] = sorted[k + 1] }
        let leaves = (0 ... values.count).map { i -> Fr in
            let v = i == 0 ? Fr.zero : values[i - 1]
            let n = next[i]
            return n == 0 ? PrivacyHash.nfLeaf(value: v, nextValue: .zero, nextIndex: 0)
                : PrivacyHash.nfLeaf(value: v, nextValue: values[n - 1], nextIndex: UInt64(n))
        }
        let t = MerkleTree(store: MemNodeStore())
        t.appendAll(leaves)
        self.values = values; self.keys = keys; self.sorted = sorted; self.tree = t
    }

    /// The leaf count, sentinel included (the chain's nf_size; 1 when nothing was inserted).
    public var size: UInt64 { tree.size }

    public func root() -> Fr { tree.root() }

    private func key(_ index: Int) -> BigUInt { index == 0 ? 0 : keys[index - 1] }

    private func value(_ index: Int) -> Fr { index == 0 ? .zero : values[index - 1] }

    /// The slot of `v` in `sorted` (found) or where it would go.
    private func search(_ v: Fr) -> (found: Bool, at: Int) {
        let k = v.bigUInt
        var lo = 0, hi = sorted.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            let c = key(sorted[mid])
            if c < k { lo = mid + 1 } else if c > k { hi = mid - 1 } else { return (true, mid) }
        }
        return (false, lo)
    }

    /// Whether `v` was inserted.
    public func contains(_ v: Fr) -> Bool { search(v).found }

    /// `v`'s low leaf and its path; nil when `v` is in the tree (or is 0, the sentinel).
    public func nonMembership(_ v: Fr) -> Witness? {
        guard !v.isZero else { return nil }
        let (found, ins) = search(v)
        guard !found else { return nil }
        let low = sorted[ins - 1] // the predecessor; the sentinel at least
        let (nv, ni): (Fr, UInt64) = ins < sorted.count ? (value(sorted[ins]), UInt64(sorted[ins])) : (.zero, 0)
        return Witness(lowValue: value(low), lowNextValue: nv, lowNextIndex: ni, lowIndex: UInt64(low), lowPath: tree.path(UInt64(low)))
    }
}
