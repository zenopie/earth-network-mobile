import Foundation

/// The transactions this wallet sent, kept on the device: what the activity
/// list is built from.
///
/// Earth's public node lists no transactions by address (a search by address
/// is a scan of that address's whole history on the only validator, round-5
/// R5-E-1), so the wallet remembers the hash of every transaction it
/// broadcast and looks each up by hash. Nothing about which address this is
/// leaves the phone beyond the lookups themselves, which name only the
/// transactions. Per address, newest first, at most ``limit``; hashes only,
/// which are public on the chain anyway. Ports `chain/SentTxLog.kt`.
public final class SentTxLog: @unchecked Sendable {
    /// The app's log, in the standard defaults.
    public static let standard = SentTxLog(defaults: .standard)
    public static let limit = 50

    private let defaults: UserDefaults
    private let lock = NSLock()

    public init(defaults: UserDefaults) {
        self.defaults = defaults
    }

    /// A tx hash as the LCD names one: 64 hex digits.
    public static func isHash(_ s: String) -> Bool {
        s.utf8.count == 64 && s.utf8.allSatisfy { (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }
    }

    private static func key(_ address: String) -> String { "sentTxs." + address }

    /// Records a broadcast the node accepted (CheckTx passed): it may still
    /// land, fail in its block, or be dropped; the lookup says which.
    public func record(_ hash: String, for address: String) {
        guard Self.isHash(hash), !address.isEmpty else { return }
        let h = hash.uppercased()
        lock.lock(); defer { lock.unlock() }
        var list = defaults.stringArray(forKey: Self.key(address)) ?? []
        list.removeAll { $0 == h }
        list.insert(h, at: 0)
        defaults.set(Array(list.prefix(Self.limit)), forKey: Self.key(address))
    }

    /// This address's sent hashes, newest first.
    public func hashes(for address: String) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return (defaults.stringArray(forKey: Self.key(address)) ?? []).filter(Self.isHash)
    }

    /// Forgets an address's log (the wallet was removed).
    public func clear(_ address: String) {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: Self.key(address))
    }
}
