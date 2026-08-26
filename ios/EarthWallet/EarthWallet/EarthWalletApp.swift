import EarthCore
import EarthUI
import SwiftUI
import UIKit

/// The app, which is almost nothing.
///
/// Everything is in `EarthUI` and `EarthCore` so it can be typechecked from the
/// command line without a simulator runtime, and so the domain layer stays
/// runnable on a Mac. What is left here is what only an app bundle can carry:
/// an entry point, an Info.plist, the NFC entitlement, the two heavyweight
/// dependencies that would sink either of those packages — Barretenberg and the
/// passport reader — and the one piece of UIKit that has no SwiftUI equivalent,
/// which orientations a screen allows.
@main
struct EarthWalletApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    init() {
        // EarthUI is a library and cannot see the delegate, so it is handed a
        // setter rather than reaching for one.
        AppOrientation.install { AppDelegate.orientations = $0 }

        // The two halves of registration that only exist in an app bundle. The
        // flow asks `PassportChip` and `PassportProving` for these rather than
        // reaching for CoreNFC and Barretenberg itself, which is what keeps
        // `EarthUI` buildable without either — and what let the whole passport
        // pipeline be checked on a Mac before a device could run any of it.
        ChipReader.install()
        DeviceProver.install()

        // Learn the node's minimum gas price before any screen quotes a fee.
        // Fees.forGas is synchronous — it is read while laying out a sheet and
        // while working out a spendable balance — so it answers from cache or a
        // fallback, and this is what fills the cache. Detached and unawaited: a
        // node that cannot be reached must not delay launch, and the fallback
        // is the right answer on this chain anyway.
        Task.detached(priority: .utility) { await Fees.prime() }

        // Fetching a rewarded ad takes seconds, so it is loaded ahead of the
        // tap — asking for one when the button is pressed makes the button look
        // broken. See RewardedAds.
        RewardedAds.preload()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                // Light only, deliberately. The palette has a full dark ramp
                // and `EarthTheme.resolve` still honours it, so this is one
                // line to remove — but the app is designed and checked in
                // light, and a half-checked dark mode is worse than none.
                //
                // Set on the window rather than on a view, so sheets presented
                // over it follow. A view-level modifier leaves anything
                // presented above it on the system appearance, which is how an
                // app ends up with a light screen and a dark confirmation.
                .preferredColorScheme(.light)
        }
    }
}

/// Orientation, which SwiftUI cannot express.
///
/// The app is portrait everywhere but the MRZ scanner, and a screen cannot
/// declare that for itself — `supportedInterfaceOrientationsFor` is asked of
/// the delegate, so the current allowance lives here and screens set it.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientations: UIInterfaceOrientationMask = .portrait

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        Self.orientations
    }
}
