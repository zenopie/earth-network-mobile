import Foundation
import Security

/// The attempt count and the backoff around it.
///
/// Ported from `wallet/utils/UnlockAttempts.kt`, including its shape: three
/// tries, then a lockout that lengthens each time it is tripped. The lockout
/// is what makes a four-digit PIN worth anything — 10,000 combinations falls
/// in seconds to something that can guess freely.
///
/// Timed on the monotonic clock, not the wall clock. The lockout used to be a
/// wall-clock deadline, so setting the date forward in Settings ended it — and
/// that took the backoff, and with it the PIN, back to 10,000 free guesses.
/// `CLOCK_MONOTONIC` cannot be set and keeps counting through sleep, but it
/// restarts at zero on boot; so the deadline is stored with the boot it
/// belongs to, and a reboot restarts the lockout in force rather than ending
/// it. That costs an honest user who reboots while locked out a second wait,
/// which is the price of there being nothing to gain by rebooting.
///
/// Kept in the Keychain rather than UserDefaults, device-only, so it is not a
/// plist that a backup restore or a file editor can reset.
enum UnlockAttempts {
    private static let maxAttempts = 3
    private static let backoff: [TimeInterval] = [30, 300, 900]
    private static let extended: TimeInterval = 3600

    struct Status {
        let lockedOut: Bool
        let attemptsLeft: Int
        /// Non-nil only while locked out; ready to show as-is.
        let message: String?
    }

    private struct State: Codable {
        var failed = 0
        var lockouts = 0
        /// When the lockout ends, in monotonic seconds of `boot`. Zero when
        /// none is in force.
        var until: TimeInterval = 0
        /// The length of the lockout in force, which a reboot restarts.
        var wait: TimeInterval = 0
        var boot = ""
    }

    static func status() -> Status {
        var state = load()
        let now = uptime()

        if state.until != 0 {
            // A different boot, or more time left than the lockout ever had —
            // either way the clock this deadline was set on is gone.
            if state.boot != bootID || state.until - now > state.wait {
                state.until = now + state.wait
                state.boot = bootID
                save(state)
            }

            let remaining = state.until - now
            if remaining > 0 {
                return Status(
                    lockedOut: true,
                    attemptsLeft: 0,
                    message: "Too many attempts. Try again in \(duration(remaining))."
                )
            }

            // The window elapsed. Clear it so the next wrong PIN starts a fresh
            // count rather than tripping the lockout again immediately.
            state.until = 0
            state.wait = 0
            state.failed = 0
            save(state)
        }

        return Status(
            lockedOut: false,
            attemptsLeft: maxAttempts - state.failed,
            message: nil
        )
    }

    @discardableResult
    static func recordFailure() -> Status {
        var state = load()
        state.failed += 1

        if state.failed >= maxAttempts {
            let wait = state.lockouts < backoff.count ? backoff[state.lockouts] : extended
            state.failed = 0
            state.lockouts += 1
            state.wait = wait
            state.until = uptime() + wait
            state.boot = bootID
        }
        save(state)
        return status()
    }

    static func recordSuccess() {
        SecItemDelete(query as CFDictionary)
        clearLegacy()
    }

    private static func duration(_ seconds: TimeInterval) -> String {
        let whole = Int(seconds.rounded(.up))
        if whole >= 3600 { return "\(whole / 3600)h" }
        if whole >= 60 { return "\(whole / 60)m" }
        return "\(whole)s"
    }

    // MARK: - clock

    /// Seconds since boot, including sleep. Not settable by the user.
    private static func uptime() -> TimeInterval {
        TimeInterval(clock_gettime_nsec_np(CLOCK_MONOTONIC)) / 1_000_000_000
    }

    /// Names this boot. Empty if the kernel will not say, in which case a
    /// reboot is still caught by the deadline landing further off than the
    /// lockout's own length.
    private static let bootID: String = {
        var size = 0
        guard sysctlbyname("kern.bootsessionuuid", nil, &size, nil, 0) == 0, size > 0 else {
            return ""
        }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname("kern.bootsessionuuid", &buffer, &size, nil, 0) == 0 else { return "" }
        return String(cString: buffer)
    }()

    // MARK: - storage

    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "network.erth.wallet",
            kSecAttrAccount as String: "unlock-attempts",
        ]
    }

    private static func load() -> State {
        var lookup = query
        lookup[kSecReturnData as String] = true
        var item: CFTypeRef?
        if SecItemCopyMatching(lookup as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let state = try? JSONDecoder().decode(State.self, from: data) {
            return state
        }
        return migrated()
    }

    private static func save(_ state: State) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        // Not the vault's `WhenPasscodeSet`: this has to be writable on a
        // device that has lost its passcode, or a wrong PIN would go uncounted.
        let changes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if SecItemUpdate(query as CFDictionary, changes as CFDictionary) == errSecItemNotFound {
            var attributes = query
            attributes.merge(changes) { $1 }
            SecItemAdd(attributes as CFDictionary, nil)
        }
    }

    // The UserDefaults keys this used to live under. Carried over once so an
    // update does not hand out a fresh set of attempts, then removed.
    private static let legacyKeys = ["unlock.failed", "unlock.lockouts", "unlock.until"]

    private static func migrated() -> State {
        let defaults = UserDefaults.standard
        guard legacyKeys.contains(where: { defaults.object(forKey: $0) != nil }) else { return State() }

        var state = State()
        state.failed = defaults.integer(forKey: "unlock.failed")
        state.lockouts = defaults.integer(forKey: "unlock.lockouts")
        let left = defaults.double(forKey: "unlock.until") - Date().timeIntervalSince1970
        if left > 0 {
            state.wait = min(left, extended)
            state.until = uptime() + state.wait
            state.boot = bootID
        }
        save(state)
        clearLegacy()
        return state
    }

    private static func clearLegacy() {
        for key in legacyKeys {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}
