#if canImport(GoogleMobileAds) && os(iOS)
import GoogleMobileAds
import UIKit

/// The "ads for gas" rewarded ad, held for the process rather than by a screen.
///
/// A new human has no ERTH and, more awkwardly, no on-chain account: an address
/// the chain has never seen cannot sign anything at all, because the ante
/// handler rejects an unknown signer before it looks at who is paying. So the
/// fee cannot simply be waived — something has to put coins there. A completed
/// ad view, attested by Google's signature straight to the backend, is what
/// buys them.
///
/// The wallet address rides along as SSV `customData`. That is the whole
/// binding between the ad and the grant: Google calls the backend's
/// `/ads-callback` with it, the backend verifies the signature, dedupes on
/// `transaction_id`, and sends the dust. Nothing the app says is trusted.
@MainActor
public enum RewardedAds {

    /// The iOS rewarded unit. NOT Android's — an ad unit belongs to one
    /// platform, and Android's `ca-app-pub-8662126294069074/9040854138` will
    /// never fill here. Create the iOS unit in the AdMob console and paste it.
    ///
    /// Until then this is Google's public test unit, which always fills and
    /// pays nothing: the flow is exercisable end to end except for the grant,
    /// because the backend only ever sees a callback for a real unit.
    public static var adUnitID = "ca-app-pub-3940256099942544/1712485313"

    private static var ad: RewardedAd?
    private static var loading = false
    private static var delegate: Delegate?

    public static var isReady: Bool { ad != nil }

    /// Loads an ad if one is not already loaded or in flight. Safe to call often.
    ///
    /// Called at launch, because fetching a rewarded ad takes seconds and doing
    /// it when the button is pressed makes the button look broken.
    public static func preload() {
        guard ad == nil, !loading else { return }
        loading = true
        Task {
            defer { loading = false }
            // The SDK has to be started before a request is made. Idempotent,
            // so calling it on every preload costs nothing after the first and
            // saves an ordering rule nobody would remember.
            await startIfNeeded()
            do {
                ad = try await RewardedAd.load(with: adUnitID, request: Request())
            } catch {
                ad = nil
            }
        }
    }

    private static var started = false

    private static func startIfNeeded() async {
        guard !started else { return }
        started = true
        await MobileAds.shared.start()
    }

    /// Shows the ad, attaching `walletAddress` as SSV custom data.
    ///
    /// `completion` reports whether the *reward was earned* — not whether the
    /// dust arrived. The grant happens out of band when Google calls the
    /// backend, so the caller has to watch the chain for the funds. See
    /// `TxController.awaitGas`.
    public static func show(
        from viewController: UIViewController,
        walletAddress: String,
        completion: @escaping (Bool) -> Void
    ) {
        guard let current = ad else {
            // No ad in hand: reload for next time and report failure rather
            // than silently doing nothing, which is indistinguishable from a
            // dead button.
            preload()
            completion(false)
            return
        }

        let options = ServerSideVerificationOptions()
        options.customRewardText = walletAddress
        current.serverSideVerificationOptions = options

        // Set before present, not after: the delegate has to be in place before
        // the ad can possibly dismiss, or a fast dismissal reports nothing and
        // the caller waits forever on a completion that never fires.
        let earned = Box()
        let handler = Delegate {
            ad = nil
            preload()
            completion(earned.value)
        }
        delegate = handler
        current.fullScreenContentDelegate = handler

        current.present(from: viewController) { earned.value = true }
    }

    /// The reward flag, shared between the present callback and the dismissal
    /// delegate. A box rather than a captured `var` so both closures are
    /// unambiguously looking at the same value.
    private final class Box { var value = false }

    /// Full-screen callbacks, which the SDK requires as a delegate object.
    ///
    /// Dismissal and failure-to-present are the same outcome here: the ad is
    /// gone, a fresh one should be loaded, and the caller is told whether the
    /// reward was earned. Only the reward flag distinguishes them.
    private final class Delegate: NSObject, FullScreenContentDelegate {
        private let onFinished: () -> Void

        init(onFinished: @escaping () -> Void) { self.onFinished = onFinished }

        func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
            onFinished()
        }

        func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
            onFinished()
        }
    }
}
#endif
