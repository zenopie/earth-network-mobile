import Foundation

/// Where a referrer handle may come from (audit 5, M5): only the verified
/// universal link `https://erth.network/ref/<handle>`, exactly. Ports
/// `Referral.handleFromLink` in `referral/Referral.kt`; there is no custom
/// scheme (an unverified one lets any page fire a link first and lock in
/// its own referrer).
public enum ReferralLink {
    public static let host = "erth.network"

    /// The handle of an `https://erth.network/ref/<handle>` link, else nil.
    public static func handle(fromLink link: String?) -> String? {
        guard let link, let u = URLComponents(string: link) else { return nil }
        guard u.scheme == "https", u.host == host, u.port == nil, u.user == nil, u.password == nil else { return nil }
        let path = u.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let parts = path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, parts[0] == "ref", let h = Handles.parse(parts[1]), h == parts[1] else { return nil }
        return h
    }
}
