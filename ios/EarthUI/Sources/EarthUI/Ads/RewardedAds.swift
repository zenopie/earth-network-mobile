#if canImport(GoogleMobileAds) && os(iOS)
import AdSupport
import CryptoKit
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
    /// never fill here.
    ///
    /// Not Google's demo unit either, tempting as it is during development.
    /// The SSV callback URL belongs to an ad unit, the demo unit is not ours to
    /// configure, and the backend checks the `ad_unit` it is called with
    /// against its own `ADMOB_AD_UNIT_ID`. An ad would fill and no dust would
    /// ever arrive. See `testDeviceIdentifiers` for the arrangement that gets
    /// both.
    public static var adUnitID = "ca-app-pub-8662126294069074/1484036231"

    /// Development phones registered with AdMob as test devices.
    ///
    /// The live unit answers "No fill" to an ordinary development phone, and
    /// the gas gate turns a failed load into a silent no-op — so the button is
    /// dead for the whole of development with nothing on screen to say why.
    /// Registering the device makes that *same* unit serve test ads, which fill
    /// every time and still drive the server-side verification callback. Test
    /// ads on the real unit are the only arrangement where both the ad and the
    /// dust arrive; this is what `TEST_DEVICE_IDS` does on Android.
    ///
    /// iOS identifiers are per-device, so unlike Android's they cannot be
    /// written down in advance. The SDK prints this device's on the first ad
    /// request:
    ///
    ///     <Google> To get test ads on this device, set:
    ///     GADMobileAds.sharedInstance.requestConfiguration.testDeviceIdentifiers = @[ "…" ]
    ///
    /// but that goes to the unified log, which needs root to read off a device,
    /// so `debugIdentifiers` below derives it instead. Anything set here is
    /// registered as well, for the case where that derivation stops matching.
    /// Debug builds only — see `startIfNeeded`.
    public static var testDeviceIdentifiers: [String] = []

    /// The identifiers this device would be known by, derived rather than
    /// copied out of a log.
    ///
    /// Google hashes a device id to make the test identifier. Which one has
    /// changed across SDK versions — the advertising id when it is available,
    /// the vendor id when it is not, and on a phone that never granted App
    /// Tracking Transparency the advertising id is all zeroes. Rather than pick,
    /// this returns every candidate: the SDK matches the request against a
    /// *list*, so an identifier that is not the right one is inert, and
    /// including all of them means the right one is always among them.
    ///
    /// Printed as well as registered, because `print` reaches stderr and
    /// `devicectl --console` captures that — which is the only way to read this
    /// off a device without root.
    static var debugIdentifiers: [String] {
        var ids: [String] = []
        if let vendor = UIDevice.current.identifierForVendor?.uuidString {
            ids.append(md5(vendor))
            ids.append(md5(vendor.lowercased()))
        }
        let advertising = ASIdentifierManager.shared().advertisingIdentifier.uuidString
        // All-zeroes is what an ungranted ATT prompt yields; hashing it would
        // register an identifier every such device shares.
        if advertising != "00000000-0000-0000-0000-000000000000" {
            ids.append(md5(advertising))
            ids.append(md5(advertising.lowercased()))
        }
        return Array(Set(ids))
    }

    private static func md5(_ text: String) -> String {
        Insecure.MD5.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

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
        #if DEBUG
        // Debug builds only. A release build that registered a test device
        // would serve that device test ads in production, and the grants
        // behind them are real dust.
        let identifiers = Array(Set(testDeviceIdentifiers + debugIdentifiers))
        if !identifiers.isEmpty {
            MobileAds.shared.requestConfiguration.testDeviceIdentifiers = identifiers
            print("[ads] registered test device identifiers: \(identifiers.joined(separator: ", "))")
        }
        #endif
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
