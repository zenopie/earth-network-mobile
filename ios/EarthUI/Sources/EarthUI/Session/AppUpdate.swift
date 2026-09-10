import Foundation
import Observation

/// "There is a newer build on the App Store", and nothing stronger.
///
/// Android gates launch on Play's in-app update API and can run the IMMEDIATE
/// flow, which blocks until the user updates. iOS has no equivalent: Apple
/// exposes no way to trigger, force, or even observe an update, so the only
/// thing available is to ask the public iTunes lookup what version is live and
/// say so. Hence a dismissible banner rather than a gate.
///
/// This is deliberately *soft*. It cannot stop a stale build from being used,
/// which matters because a build can go stale in a way the user cannot see —
/// on 2026-09-09 an iOS build one day older than a circuit recompile failed
/// registration with a chain error about DSC binding, which means nothing to
/// the person holding the phone. Closing that hole needs a minimum-version
/// check served by the backend, which knows what the chain currently accepts;
/// the store's version number does not.
public enum AppUpdate {

    /// A newer version, and where to get it.
    public struct Available: Equatable, Sendable {
        public let version: String
        public let storeURL: URL
    }

    /// Apple's public lookup. No key, no entitlement, and it answers for any
    /// bundle id — including one that is not published, which is the case that
    /// matters here: an unreleased app returns `resultCount: 0` and this
    /// returns nil rather than erroring, so the banner simply never appears
    /// until there is a store listing to point at.
    static let lookupHost = "https://itunes.apple.com/lookup"

    /// Returns the live version if it is newer than `current`, else nil.
    ///
    /// Every failure — offline, rate-limited, malformed, not published — is
    /// nil. A version check is the last thing that should surface an error: it
    /// is not what the user opened the app to do, and being unable to reach
    /// Apple says nothing about whether this build works.
    static func check(
        bundleID: String,
        current: String,
        session: URLSession = .shared
    ) async -> Available? {
        // Cache-busting: the lookup is edge-cached for a few hours, and a
        // response held from before a release is the one case where being
        // wrong is visible — the user updates and is told to update again.
        guard var components = URLComponents(string: lookupHost) else { return nil }
        components.queryItems = [
            URLQueryItem(name: "bundleId", value: bundleID),
            URLQueryItem(name: "t", value: String(Int(Date().timeIntervalSince1970))),
        ]
        guard let url = components.url else { return nil }

        var request = URLRequest(url: url)
        request.timeoutInterval = 10
        request.cachePolicy = .reloadIgnoringLocalCacheData

        guard
            let (data, response) = try? await session.data(for: request),
            let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let results = root["results"] as? [[String: Any]],
            let first = results.first,
            let latest = first["version"] as? String,
            let trackURL = (first["trackViewUrl"] as? String).flatMap(URL.init(string:))
        else { return nil }

        guard isNewer(latest, than: current) else { return nil }
        return Available(version: latest, storeURL: trackURL)
    }

    /// Numeric component-wise comparison of two dotted versions.
    ///
    /// Compared as integers rather than strings because "0.10.0" is newer than
    /// "0.9.0" and sorts before it. Missing trailing components read as zero,
    /// so "1.0" and "1.0.0" are the same version. Anything non-numeric makes
    /// the comparison unanswerable, and an unanswerable comparison returns
    /// false — no banner — rather than guessing.
    static func isNewer(_ candidate: String, than current: String) -> Bool {
        let lhs = candidate.split(separator: ".").map { Int($0) }
        let rhs = current.split(separator: ".").map { Int($0) }
        guard !lhs.contains(nil), !rhs.contains(nil), !lhs.isEmpty, !rhs.isEmpty else {
            return false
        }
        let a = lhs.compactMap { $0 }, b = rhs.compactMap { $0 }
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0
            let y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }
}

/// Holds the banner's state for one run of the app.
@Observable
@MainActor
final class AppUpdateModel {

    /// Non-nil once a newer version is known and the user has not dismissed it.
    private(set) var available: AppUpdate.Available?

    /// Dismissal is remembered per version, not as a flag. A user who waves off
    /// 1.2.0 has said something about 1.2.0; they have said nothing about the
    /// 1.3.0 that follows, and treating the two alike would silence the banner
    /// for good after one tap.
    private static let dismissedKey = "appUpdate.dismissedVersion"

    /// Checked once per launch. The store version does not change while the
    /// app is open, and re-checking on every foreground would spend a request
    /// to learn what it already knows.
    private var checked = false

    func check(defaults: UserDefaults = .standard, bundle: Bundle = .main) async {
        guard !checked else { return }
        checked = true

        guard
            let bundleID = bundle.bundleIdentifier,
            let current = bundle.infoDictionary?["CFBundleShortVersionString"] as? String
        else { return }

        guard let found = await AppUpdate.check(bundleID: bundleID, current: current) else {
            return
        }
        guard defaults.string(forKey: Self.dismissedKey) != found.version else { return }
        available = found
    }

    func dismiss(defaults: UserDefaults = .standard) {
        if let version = available?.version {
            defaults.set(version, forKey: Self.dismissedKey)
        }
        available = nil
    }
}
