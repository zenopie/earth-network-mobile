import SwiftUI

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
/// So the view is replaced with an opaque panel for any scene phase that is not
/// `.active`. The snapshot is taken during `.inactive`, which is why covering
/// only on `.background` is too late.
///
/// Screenshots are left alone deliberately. They are not preventable, and a
/// user photographing their own phrase is a choice the platform lets them make.
private struct PrivacyCover: ViewModifier {
    @Environment(\.earth) private var theme
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.overlay {
            if scenePhase != .active {
                theme.colors.bgPrimary
                    .ignoresSafeArea()
                    .overlay(
                        Image(systemName: "lock.fill")
                            .font(.largeTitle)
                            .foregroundStyle(theme.colors.textTertiary)
                    )
                    .transition(.identity)
            }
        }
    }
}

public extension View {
    /// Covers this view whenever the app is not frontmost, so it stays out of
    /// the multitasking snapshot. Use on anything showing a recovery phrase or
    /// a PIN.
    func privacyCovered() -> some View {
        modifier(PrivacyCover())
    }
}
