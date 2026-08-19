import SwiftUI
import UIKit

/// Lets one screen ask for an orientation the rest of the app does not allow.
///
/// The MRZ zone is two 44-character lines across a 125mm page. In portrait the
/// frame is barely wider than the passport is tall, so the text lands too small
/// for the recognizer; turned the long way it fills the frame. This is the only
/// screen that needs it, so it asks rather than the app permitting it
/// everywhere.
struct OrientationLock: ViewModifier {
    let mask: UIInterfaceOrientationMask
    /// What to turn to on the way in. Nil leaves the current orientation alone.
    let preferred: UIInterfaceOrientationMask?

    func body(content: Content) -> some View {
        content
            .onAppear { apply(mask, turningTo: preferred) }
            .onDisappear { apply(.portrait, turningTo: .portrait) }
    }

    private func apply(_ mask: UIInterfaceOrientationMask, turningTo preferred: UIInterfaceOrientationMask?) {
        // The delegate is asked for this, so the allowance is set before the
        // window is told to re-ask.
        AppOrientation.allow(mask)

        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first
        else { return }

        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        if let preferred {
            // Requesting the geometry is what actually turns it; widening the
            // mask alone only permits the user to turn it themselves.
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: preferred))
        }
    }
}

/// The seam to the app's delegate.
///
/// `EarthUI` is a library and cannot see the delegate type, so the app hands
/// this a setter at launch. Without one it is a no-op, which is what a preview
/// or a test wants anyway.
public enum AppOrientation {
    nonisolated(unsafe) static var setter: ((UIInterfaceOrientationMask) -> Void)?

    public static func install(_ setter: @escaping (UIInterfaceOrientationMask) -> Void) {
        Self.setter = setter
    }

    static func allow(_ mask: UIInterfaceOrientationMask) {
        setter?(mask)
    }
}

extension View {
    func orientationLock(
        _ mask: UIInterfaceOrientationMask,
        turningTo preferred: UIInterfaceOrientationMask? = nil
    ) -> some View {
        modifier(OrientationLock(mask: mask, preferred: preferred))
    }
}
