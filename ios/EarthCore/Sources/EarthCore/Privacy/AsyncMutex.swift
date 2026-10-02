import Foundation

/// A FIFO async lock: what `@Synchronized` is to the Kotlin wallet. Not
/// reentrant — the wallet locks once at each public entry point and calls
/// unlocked helpers inside.
public actor AsyncMutex {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init() {}

    private func lock() async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func unlock() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }

    public func withLock<T>(_ body: () async throws -> T) async rethrows -> T {
        await lock()
        do {
            let r = try await body()
            unlock()
            return r
        } catch {
            unlock()
            throw error
        }
    }
}
