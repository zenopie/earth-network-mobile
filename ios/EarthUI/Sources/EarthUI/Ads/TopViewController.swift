#if os(iOS)
import UIKit

extension UIApplication {

    /// The view controller a full-screen ad should be presented from.
    ///
    /// SwiftUI has no equivalent: `present(from:)` wants a UIViewController and
    /// a SwiftUI view has none to give. Walking to the top of the presentation
    /// chain matters because the gas gate is itself inside a sheet — presenting
    /// from the window's root while a sheet is up throws
    /// "attempt to present ... which is already presenting", and the ad never
    /// appears.
    var topViewController: UIViewController? {
        let scene = connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
#endif
