import EarthUI
import SwiftUI

/// The app, which is almost nothing.
///
/// Everything is in `EarthUI` and `EarthCore` so it can be typechecked from the
/// command line without a simulator runtime, and so the domain layer stays
/// runnable on a Mac. What is left here is what only an app bundle can carry:
/// an entry point, an Info.plist, and eventually the NFC entitlement.
@main
struct EarthWalletApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
