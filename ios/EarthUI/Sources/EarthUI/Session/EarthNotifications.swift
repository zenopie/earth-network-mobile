import EarthCore
import Foundation
import Observation
import UserNotifications
#if os(iOS)
import BackgroundTasks
#endif

/// Local notifications: the day's ANML opening and a proposal opening for
/// votes. Both say something is there to do; neither does it.
///
/// Nothing is sent to a push service. The claim opens at a time this phone
/// already knows (UTC midnight, from the wallet's own record), so those are
/// scheduled ahead; proposals are read from the public LCD — the same list the
/// Govern tab reads, nothing about this wallet — on each refresh and on a
/// background refresh when iOS grants one.
@MainActor
public enum EarthNotifications {
    /// Info.plist's BGTaskSchedulerPermittedIdentifiers must list this.
    public static let refreshTask = "network.erth.EarthWallet.proposals"
    private static let anmlPrefix = "anml-ready-"
    /// Claim openings scheduled ahead: enough to cover a week of not opening the app.
    private static let anmlDays = 7
    private static let notifiedKey = "notifiedProposals"
    private static let delegate = Delegate()

    public enum Kind: String { case anml, proposal }

    /// The two switches in Settings > Notifications, both on until turned off.
    static var anmlEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "notifyAnml") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "notifyAnml") }
    }

    static var proposalsEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "notifyProposals") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "notifyProposals") }
    }

    /// Where a tapped notification asked to go, until the tabs take it.
    @Observable
    @MainActor
    final class Router {
        static let shared = Router()
        var tab: Tab?
    }

    /// From the app's launch: the delegate (so a notification shows while
    /// the app is open, and a tap is routed) and the background refresh,
    /// which iOS only accepts before launch finishes.
    public static func install() {
        UNUserNotificationCenter.current().delegate = delegate
        #if os(iOS)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshTask, using: nil) { task in
            Task { @MainActor in handle(task) }
        }
        #endif
    }

    /// Asks once; iOS remembers the answer and never shows the prompt again.
    static func requestAuthorization(force: Bool = false) async {
        #if targetEnvironment(simulator)
        // A demo launch is looked at, not tapped: the prompt would cover it.
        if !force, UserDefaults.standard.string(forKey: "demoWallet") != nil { return }
        #endif
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    private static func authorized() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
    }

    // MARK: - ANML

    /// One notification per claim opening for the next week, from
    /// `claimOpensAt` (PrivacyWallet.claimOpensAt: 0 open now, nil without a
    /// live registration, which cancels them). Replaced on every sync, so a
    /// claim made or a registration lapsed is reflected at once.
    static func scheduleAnml(claimOpensAt: Int64?) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: (0 ..< anmlDays).map { anmlPrefix + String($0) })
        guard anmlEnabled, let opens = claimOpensAt else { return }
        let now = Int64(Date().timeIntervalSince1970)
        // Open now: the Claim card is lit, so the first reminder is tomorrow's.
        let first = opens == 0 ? Personhood.nextClaimOpensAt() : opens
        for day in 0 ..< anmlDays {
            let at = first + Int64(day) * 86_400
            guard at > now else { continue }
            let content = UNMutableNotificationContent()
            content.title = "ANML ready"
            content.body = "Today's ANML is ready to claim."
            content.sound = .default
            content.userInfo = ["kind": Kind.anml.rawValue]
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(at - now), repeats: false)
            center.add(UNNotificationRequest(identifier: anmlPrefix + String(day), content: content, trigger: trigger))
        }
    }

    // MARK: - proposals

    /// Notifies each proposal in its voting period that has not been
    /// notified before. Marked only once a notification could be shown, so
    /// granting permission later still brings the open ones.
    static func notifyNewProposals(_ proposals: [Gov.Proposal]) async {
        let open = proposals.filter(\.isLive)
        // Off: marked as seen all the same, so turning it back on does not
        // announce what opened meanwhile as new.
        guard proposalsEnabled else {
            markSeen(open.map(\.id))
            return
        }
        guard !open.isEmpty, await authorized() else { return }
        let defaults = UserDefaults.standard
        var seen = Set((defaults.array(forKey: notifiedKey) as? [NSNumber] ?? []).map(\.uint64Value))
        for p in open where !seen.contains(p.id) {
            let content = UNMutableNotificationContent()
            content.title = "New proposal"
            content.body = "#\(p.id) \(p.title) is open for voting."
            content.sound = .default
            content.userInfo = ["kind": Kind.proposal.rawValue]
            try? await UNUserNotificationCenter.current()
                .add(UNNotificationRequest(identifier: "proposal-\(p.id)", content: content, trigger: nil))
            seen.insert(p.id)
        }
        defaults.set(seen.sorted().map { NSNumber(value: $0) }, forKey: notifiedKey)
    }

    private static func markSeen(_ ids: [UInt64]) {
        guard !ids.isEmpty else { return }
        let defaults = UserDefaults.standard
        let seen = Set((defaults.array(forKey: notifiedKey) as? [NSNumber] ?? []).map(\.uint64Value)).union(ids)
        defaults.set(seen.sorted().map { NSNumber(value: $0) }, forKey: notifiedKey)
    }

    #if os(iOS)
    /// Asks iOS for the next background refresh. It decides when, if ever:
    /// this is a request for no sooner than six hours, not a schedule.
    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handle(_ task: BGTask) {
        scheduleRefresh()
        let work = Task { @MainActor in
            await notifyNewProposals(await EarthClient().proposals(limit: 20))
            task.setTaskCompleted(success: !Task.isCancelled)
        }
        task.expirationHandler = { work.cancel() }
    }
    #endif

    private final class Delegate: NSObject, UNUserNotificationCenterDelegate {
        func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
            completionHandler([.banner, .sound])
        }

        func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                    withCompletionHandler completionHandler: @escaping () -> Void) {
            let kind = (response.notification.request.content.userInfo["kind"] as? String).flatMap(Kind.init)
            Task { @MainActor in
                Router.shared.tab = kind == .proposal ? .govern : .wallet
                completionHandler()
            }
        }
    }
}
