import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Hides a view from the app-switcher snapshot while it is on screen.
///
/// iOS gives no equivalent of Android's `FLAG_SECURE`: a screenshot cannot be
/// prevented, only noticed after the fact. What *can* be prevented is the other
/// half of the same exposure, and it is the half that happens without anyone
/// deciding to do it — when the app leaves the foreground the system snapshots
/// the current view to draw the multitasking card, and that image outlives the
/// moment. A recovery phrase left on screen while the user switches away to
/// write it down ends up in that card.
///
/// So an opaque panel goes over the app for any scene phase that is not
/// `.active`. The snapshot is taken during `.inactive`, which is why covering
/// only on `.background` is too late.
///
/// On iOS the panel is its own window, above the app's. It used to be an
/// overlay on the root view, and a SwiftUI overlay only covers the view it is
/// attached to — a `.sheet` is presented in a separate UIKit container above
/// it, so the phrase-reveal sheet sat on top of the cover and went into the
/// snapshot uncovered. A window at `.alert + 1` is above every presentation
/// the app can make, sheets and alerts included.
///
/// Screenshots are left alone deliberately. They are not preventable, and a
/// user photographing their own phrase is a choice the platform lets them make.
private struct PrivacyCover: ViewModifier {
    @Environment(\.earth) private var theme
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        #if os(iOS)
        content.onAppear { PrivacyShield.install(theme: theme) }
        #else
        content.overlay {
            if scenePhase != .active {
                PrivacyPanel().transition(.identity)
            }
        }
        #endif
    }
}

private struct PrivacyPanel: View {
    @Environment(\.earth) private var theme

    var body: some View {
        theme.colors.bgPrimary
            .ignoresSafeArea()
            .overlay(
                Image(systemName: "lock.fill")
                    .font(.largeTitle)
                    .foregroundStyle(theme.colors.textTertiary)
            )
    }
}

#if os(iOS)
/// The cover's window, shown and hidden from the scene's own notifications.
///
/// Notifications rather than `scenePhase`: `willDeactivate` is posted
/// synchronously before the system takes the snapshot, whereas a SwiftUI
/// phase change is delivered through a view update that is not promised to
/// have reached the screen by then.
@MainActor
private enum PrivacyShield {
    private static var window: UIWindow?
    private static var observers: [NSObjectProtocol] = []

    static func install(theme: EarthTheme) {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(
                forName: UIScene.willDeactivateNotification, object: nil, queue: .main
            ) { note in
                let scene = note.object as? UIWindowScene
                MainActor.assumeIsolated { show(in: scene, theme: theme) }
            },
            center.addObserver(
                forName: UIScene.didActivateNotification, object: nil, queue: .main
            ) { _ in
                MainActor.assumeIsolated { hide() }
            },
        ]
    }

    private static func show(in scene: UIWindowScene?, theme: EarthTheme) {
        guard let scene else { return }
        if window?.windowScene !== scene {
            let cover = UIWindow(windowScene: scene)
            cover.windowLevel = .alert + 1
            let host = UIHostingController(rootView: PrivacyPanel().environment(\.earth, theme))
            host.view.backgroundColor = .clear
            cover.rootViewController = host
            window = cover
        }
        // Shown without becoming key, so the app's own window keeps focus and
        // nothing is left pointing at the cover when it goes away.
        window?.isHidden = false
    }

    private static func hide() {
        window?.isHidden = true
    }
}
#endif

public extension View {
    /// Covers this view whenever the app is not frontmost, so it stays out of
    /// the multitasking snapshot. Use on anything showing a recovery phrase or
    /// a PIN.
    func privacyCovered() -> some View {
        modifier(PrivacyCover())
    }
}
