import Foundation

/// The depth-32 binary Poseidon2 tree of the chain's zk/merkle, shared by the
/// identity tree (updatable: leaves are zeroed) and the note tree
/// (append-only). Ports `privacy/zk/Merkle.kt`:
///
///     node = Poseidon2([left, right])    empty leaf = 0
///     zero[i+1] = H(zero[i], zero[i])
///     bit i of the index = 1  <=>  the running node is the right child at level i
///
/// The wallet rebuilds both trees from the indexer's full streams and takes
/// its own paths locally, so nothing it asks for names a leaf of its own.
public enum Merkle {
    public static let depth = 32
    public static let capacity: UInt64 = 1 << 32

    public static let zero: [Fr] = {
        var z: [Fr] = [.zero]
        for i in 0 ..< depth { z.append(node(z[i], z[i])) }
        return z
    }()

    @inline(__always)
    public static func node(_ l: Fr, _ r: Fr) -> Fr { Poseidon2.hash([l, r]) }

    /// Recomputes a root the way the circuit does (privacy_core::merkle_root).
    public static func rootFromPath(leaf: Fr, index: UInt64, siblings: [Fr]) -> Fr {
        precondition(siblings.count == depth)
        var cur = leaf
        for lvl in 0 ..< depth {
            cur = (index >> UInt64(lvl)) & 1 == 0 ? node(cur, siblings[lvl]) : node(siblings[lvl], cur)
        }
        return cur
    }
}

/// Non-empty tree nodes; level 0 is leaves, level 32 the root. A node never
/// written reads as nil.
public protocol NodeStore: AnyObject {
    func get(_ level: Int, _ index: UInt64) -> Fr?
    func set(_ level: Int, _ index: UInt64, _ v: Fr)
    /// Makes every set so far durable; a no-op for memory.
    func flush()
    /// Drops every node.
    func clear()
}

public final class MemNodeStore: NodeStore {
    private var m: [UInt64: Fr] = [:]
    public init() {}
    private func key(_ level: Int, _ index: UInt64) -> UInt64 { UInt64(level) << 40 | index }
    public func get(_ level: Int, _ index: UInt64) -> Fr? { m[key(level, index)] }
    public func set(_ level: Int, _ index: UInt64, _ v: Fr) { m[key(level, index)] = v }
    public func flush() {}
    public func clear() { m.removeAll() }
}

/// One file per level, node i at byte offset 32*i. Nothing above level 0 can
/// hash to 0 without a Poseidon2 preimage, so 32 zero bytes there (a hole, or
/// past the end) read as unwritten; at level 0 they read as the empty leaf,
/// which is what a zeroed identity leaf is.
public final class FileNodeStore: NodeStore {
    private let dir: URL
    private var handles: [Int: FileHandle] = [:]

    public init(directory: URL) {
        dir = directory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    deinit { close() }

    private func handle(_ level: Int) -> FileHandle {
        if let h = handles[level] { return h }
        let url = dir.appendingPathComponent("level\(level)")
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        let h = try! FileHandle(forUpdating: url)
        handles[level] = h
        return h
    }

    public func get(_ level: Int, _ index: UInt64) -> Fr? {
        let h = handle(level)
        let off = index * 32
        guard let end = try? h.seekToEnd(), off + 32 <= end else { return nil }
        try? h.seek(toOffset: off)
        guard let b = try? h.read(upToCount: 32), b.count == 32 else { return nil }
        if b.allSatisfy({ $0 == 0 }) { return level == 0 ? .zero : nil }
        return try? Fr(bytes: b)
    }

    public func set(_ level: Int, _ index: UInt64, _ v: Fr) {
        let h = handle(level)
        try? h.seek(toOffset: index * 32)
        try? h.write(contentsOf: v.bytes)
    }

    public func flush() { handles.values.forEach { try? $0.synchronize() } }

    public func close() {
        handles.values.forEach { try? $0.close() }
        handles.removeAll()
    }

    /// Drops everything (a resync from scratch).
    public func clear() {
        close()
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        files.forEach { try? FileManager.default.removeItem(at: $0) }
    }
}

/// An incremental sparse Merkle tree over a `NodeStore`; `size` is the append cursor.
public final class MerkleTree {
    private let store: NodeStore
    public private(set) var size: UInt64

    public init(store: NodeStore, size: UInt64 = 0) {
        self.store = store
        self.size = size
    }

    private func node(_ level: Int, _ index: UInt64) -> Fr { store.get(level, index) ?? Merkle.zero[level] }

    public func root() -> Fr { node(Merkle.depth, 0) }

    public func leaf(_ index: UInt64) -> Fr { node(0, index) }

    @discardableResult
    public func append(_ leaf: Fr) -> UInt64 {
        appendAll([leaf])
        return size - 1
    }

    /// Appends `leaves` and rehashes each level's changed range once: about two
    /// hashes a leaf for a large batch, where appending one at a time costs 32.
    public func appendAll(_ leaves: [Fr]) {
        guard !leaves.isEmpty else { return }
        precondition(size + UInt64(leaves.count) <= Merkle.capacity, "tree full")
        let start = size
        for (i, l) in leaves.enumerated() { store.set(0, start + UInt64(i), l) }
        size += UInt64(leaves.count)
        rehash(start, size - 1)
    }

    /// Overwrites an appended leaf (the identity tree zeroes with Fr.zero).
    public func update(_ index: UInt64, _ leaf: Fr) {
        precondition(index < size, "leaf \(index) not yet appended")
        store.set(0, index, leaf)
        rehash(index, index)
    }

    /// Several updates, rehashing each level's touched nodes once.
    public func updateAll(_ updates: [UInt64: Fr]) {
        guard !updates.isEmpty else { return }
        for (i, l) in updates { precondition(i < size); store.set(0, i, l) }
        var dirty = Set(updates.keys)
        for lvl in 0 ..< Merkle.depth {
            let parents = Set(dirty.map { $0 >> 1 })
            for p in parents.sorted() {
                store.set(lvl + 1, p, Merkle.node(node(lvl, 2 * p), node(lvl, 2 * p + 1)))
            }
            dirty = parents
        }
    }

    private func rehash(_ lo0: UInt64, _ hi0: UInt64) {
        var lo = lo0, hi = hi0
        for lvl in 0 ..< Merkle.depth {
            let plo = lo >> 1, phi = hi >> 1
            for p in plo ... phi {
                store.set(lvl + 1, p, Merkle.node(node(lvl, 2 * p), node(lvl, 2 * p + 1)))
            }
            lo = plo; hi = phi
        }
    }

    /// The 32 siblings from the leaf level up.
    public func path(_ index: UInt64) -> [Fr] {
        precondition(index < size, "leaf \(index) not yet appended")
        var out: [Fr] = []
        out.reserveCapacity(Merkle.depth)
        var i = index
        for lvl in 0 ..< Merkle.depth {
            out.append(node(lvl, i ^ 1))
            i >>= 1
        }
        return out
    }

    /// The node at (level, index) as it stood when the tree held `size`
    /// leaves. Append-only trees only: a subtree wholly below `size` has not
    /// changed since, one wholly above was empty, and only the nodes on the
    /// frontier between are rehashed. Used to prove against an older recorded
    /// root (a stake vote's proposal snapshot).
    private func nodeAt(_ level: Int, _ index: UInt64, _ size: UInt64) -> Fr {
        let start = index << UInt64(level)
        let end = (index + 1) << UInt64(level)
        if end <= size { return node(level, index) }
        if start >= size { return Merkle.zero[level] }
        return Merkle.node(nodeAt(level - 1, 2 * index, size), nodeAt(level - 1, 2 * index + 1, size))
    }

    /// The root when the tree held `size` leaves (append-only trees).
    public func rootAt(_ size: UInt64) -> Fr {
        precondition(size <= self.size)
        return nodeAt(Merkle.depth, 0, size)
    }

    /// `index`'s path against `rootAt(size)`.
    public func pathAt(_ index: UInt64, size: UInt64) -> [Fr] {
        precondition(size <= self.size && index < size, "leaf \(index) is not in the first \(size)")
        var out: [Fr] = []
        var i = index
        for lvl in 0 ..< Merkle.depth {
            out.append(nodeAt(lvl, i ^ 1, size))
            i >>= 1
        }
        return out
    }

    public func flush() { store.flush() }

    /// Empties the tree (a resync from scratch).
    public func clear() {
        store.clear()
        size = 0
    }
}
