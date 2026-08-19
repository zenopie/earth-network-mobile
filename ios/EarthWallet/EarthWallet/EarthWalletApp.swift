import EarthUI
import SwiftUI
import UIKit

/// The app, which is almost nothing.
///
/// Everything is in `EarthUI` and `EarthCore` so it can be typechecked from the
/// command line without a simulator runtime, and so the domain layer stays
/// runnable on a Mac. What is left here is what only an app bundle can carry:
/// an entry point, an Info.plist, the NFC entitlement, and the one piece of
/// UIKit that has no SwiftUI equivalent — which orientations a screen allows.
@main
struct EarthWalletApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    init() {
        // EarthUI is a library and cannot see the delegate, so it is handed a
        // setter rather than reaching for one.
        AppOrientation.install { AppDelegate.orientations = $0 }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
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
