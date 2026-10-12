import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The window `TxOverlay` draws in: above the app's own, so above every sheet,
/// dialog and sheet-being-dismissed the app can present. See `TxOverlay` for
/// why an overlay on a view could not be.
///
/// Placed once, on the root, as a background: the probe view it adds is how it
/// finds the scene, and the root's `onChange` is what shows and hides it, since
/// a hidden window's own views are not promised an update to show themselves.
struct TxWindow: View {
    let model: AppModel
    let tx: TxController

    var body: some View {
        #if os(iOS)
        SceneProbe { scene in TxWindowHost.install(in: scene, model: model, tx: tx) }
            .frame(width: 0, height: 0)
            .onChange(of: tx.isPresenting, initial: true) { _, shown in TxWindowHost.setVisible(shown) }
        #else
        EmptyView()
        #endif
    }
}

#if os(iOS)
/// Reports the window scene it lands in.
private struct SceneProbe: UIViewRepresentable {
    let found: (UIWindowScene) -> Void

    func makeUIView(context: Context) -> Probe { Probe(found: found) }
    func updateUIView(_ uiView: Probe, context: Context) {}

    final class Probe: UIView {
        let found: (UIWindowScene) -> Void
        init(found: @escaping (UIWindowScene) -> Void) {
            self.found = found
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { nil }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let scene = window?.windowScene { found(scene) }
        }
    }
}

@MainActor
private enum TxWindowHost {
    private static var window: UIWindow?
    /// Bumped on every show, so a hide queued behind an earlier one is dropped.
    private static var generation = 0

    static func install(in scene: UIWindowScene, model: AppModel, tx: TxController) {
        guard window?.windowScene !== scene else { return }
        let w = UIWindow(windowScene: scene)
        // Above the app's window and below the privacy cover (.alert + 1),
        // which must hide this too.
        w.windowLevel = .alert
        // The app is light only (EarthWalletApp sets it on its own window,
        // which this is not).
        w.overrideUserInterfaceStyle = .light
        let host = UIHostingController(
            rootView: TxOverlay().environment(model).environment(tx).earthThemed()
        )
        host.view.backgroundColor = .clear
        w.rootViewController = host
        w.isHidden = true
        window = w
    }

    static func setVisible(_ shown: Bool) {
        generation += 1
        guard let window else { return }
        if shown {
            // A field focused in the sheet underneath keeps the keyboard up,
            // and the keyboard's window is above this one.
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            window.isHidden = false
            return
        }
        // Hidden once the card has slid away, not at once: hiding now would
        // cut the exit animation off mid-frame.
        let g = generation
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            if g == generation { window.isHidden = true }
        }
    }
}
#endif
