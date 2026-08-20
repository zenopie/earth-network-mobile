import Foundation

/// The attempt count and the backoff around it.
///
/// Ported from `wallet/utils/UnlockAttempts.kt`, including its shape: three
/// tries, then a lockout that lengthens each time it is tripped. The lockout
/// is what makes a four-digit PIN worth anything — 10,000 combinations falls
/// in seconds to something that can guess freely.
enum UnlockAttempts {
    private static let failedKey = "unlock.failed"
    private static let lockoutsKey = "unlock.lockouts"
    private static let untilKey = "unlock.until"

    private static let maxAttempts = 3
    private static let backoff: [TimeInterval] = [30, 300, 900]
    private static let extended: TimeInterval = 3600

    struct Status {
        let lockedOut: Bool
        let attemptsLeft: Int
        /// Non-nil only while locked out; ready to show as-is.
        let message: String?
    }

    static func status(now: Date = Date()) -> Status {
        let defaults = UserDefaults.standard
        let until = defaults.double(forKey: untilKey)
        let remaining = until - now.timeIntervalSince1970

        if remaining > 0 {
            return Status(
                lockedOut: true,
                attemptsLeft: 0,
                message: "Too many attempts. Try again in \(duration(remaining))."
            )
        }

        // The window elapsed. Clear it so the next wrong PIN starts a fresh
        // count rather than tripping the lockout again immediately.
        if until != 0 {
            defaults.removeObject(forKey: untilKey)
            defaults.set(0, forKey: failedKey)
        }

        return Status(
            lockedOut: false,
            attemptsLeft: maxAttempts - defaults.integer(forKey: failedKey),
            message: nil
        )
    }

    @discardableResult
    static func recordFailure(now: Date = Date()) -> Status {
        let defaults = UserDefaults.standard
        let failed = defaults.integer(forKey: failedKey) + 1
        defaults.set(failed, forKey: failedKey)

        if failed >= maxAttempts {
            let lockouts = defaults.integer(forKey: lockoutsKey)
            let wait = lockouts < backoff.count ? backoff[lockouts] : extended
            defaults.set(0, forKey: failedKey)
            defaults.set(lockouts + 1, forKey: lockoutsKey)
            defaults.set(now.timeIntervalSince1970 + wait, forKey: untilKey)
        }
        return status(now: now)
    }

    static func recordSuccess() {
        for key in [failedKey, lockoutsKey, untilKey] {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    private static func duration(_ seconds: TimeInterval) -> String {
        let whole = Int(seconds.rounded(.up))
        if whole >= 3600 { return "\(whole / 3600)h" }
        if whole >= 60 { return "\(whole / 60)m" }
        return "\(whole)s"
    }
}
