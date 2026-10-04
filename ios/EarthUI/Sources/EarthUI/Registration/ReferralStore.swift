import EarthCore
import Foundation

/// Where a referrer handle arrives from and where it is kept until
/// registration (ports `referral/Referral.kt`). Only the
/// verified universal link `https://erth.network/ref/<handle>` names one
/// (`ReferralLink`); there is no custom scheme. Kept in plain defaults: it is
/// a public handle, needed before any wallet exists.
///
/// First write wins, so a later link does not overwrite it; the registrant
/// sees it and may remove or replace it, and one that does not resolve to a
/// live handle at registration is cleared.
public enum ReferralStore {
    private static let key = "referrer_handle"

    /// The captured referrer handle (without the @), or nil.
    public static func get() -> String? { UserDefaults.standard.string(forKey: key).flatMap(Handles.parse) }

    /// Records the handle of a universal link if it is one and none is stored. Returns whether it was taken.
    @discardableResult
    public static func capture(_ url: URL?) -> Bool {
        guard let h = ReferralLink.handle(fromLink: url?.absoluteString), get() == nil else { return false }
        UserDefaults.standard.set(h, forKey: key)
        return true
    }

    /// Forgets the stored referrer: removed or replaced by the registrant, or not a live handle.
    public static func clear() { UserDefaults.standard.removeObject(forKey: key) }
}
