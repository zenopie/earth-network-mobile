import BigInt
import EarthCore
import Observation
import SwiftUI

/// One path for every transaction: confirm, broadcast, report.
///
/// Ports `ui/compose/TxController.kt`, and exists for the reason that one does:
/// before it, each screen broadcast on its own and reported the outcome in a
/// pair of toasts, so nobody could see what they were about to sign or read why
/// it failed. Keeping it in one place is also what will make the gas gate
/// universal — any transaction from an underfunded account can offer the
/// rewarded ad, not only registration.
///
/// Screens never broadcast. A screen raises an intent ("stake 100"), hands the
/// messages here, and the sheets are driven by this state — so a caller who
/// forgets to show them cannot skip the confirmation.
@Observable
@MainActor
public final class TxController {

    public struct Details: Identifiable {
        public let id = UUID()
        /// What the sheet is titled: "Send", "Swap", "Stake".
        public let action: String
        /// Label/value lines, in the order they should be read.
        public let rows: [(String, String)]
        public var gasLimit: UInt64 = TransactionSigner.defaultGasLimit

        /// Always `Fees.forGas(gasLimit)`. Derived rather than passed so the
        /// fee shown on the sheet and the fee broadcast cannot diverge — on
        /// Android they did, and claiming rewards (whose gas scales with the
        /// validator count) declared the flat default while broadcasting more.
        /// The sheet then reported the account funded when it was not.
        public var feeUerth: String { Fees.forGas(gasLimit) }

        public init(
            action: String,
            rows: [(String, String)],
            gasLimit: UInt64 = TransactionSigner.defaultGasLimit
        ) {
            self.action = action
            self.rows = rows
            self.gasLimit = gasLimit
        }
    }

    public enum Outcome: Identifiable {
        case succeeded(action: String, hash: String)
        case failed(action: String, reason: String)

        public var id: String {
            switch self {
            case let .succeeded(_, hash): hash
            case let .failed(action, reason): action + reason
            }
        }
    }

    /// Where the confirmation should draw.
    ///
    /// The sheets are one overlay, and an overlay renders inside the view it is
    /// attached to — so an overlay on the root view is *behind* anything
    /// presented over it. That is invisible until a flow is more than one sheet
    /// deep: Send and Stake reveal the root overlay by closing themselves,
    /// while Govern's slider editor sits under a stream sheet that would still
    /// be covering it.
    ///
    /// Dismissing both was tried and is not enough — landing back on the tab
    /// still drew the confirmation under it. So the confirmation is hosted
    /// wherever the request came from instead of always at the root, and the
    /// requester says which. There is still exactly one controller and one
    /// broadcast path; only the place the card draws moves.
    public enum Host: Equatable, Sendable {
        /// The root view, behind no presentation. Everything that raises a
        /// transaction from a tab or a single sheet.
        case root
        /// The allocation stream sheet, which presents the slider editor over
        /// itself.
        case allocation
        /// The identity screen, which is a sheet over the settings sheet — so
        /// the root's copy draws behind both, the same way it does under the
        /// stream editor.
        case identity
    }

    public private(set) var host: Host = .root

    /// What is waiting on the confirmation sheet, if anything.
    public private(set) var pending: Details?
    /// What came back, if anything.
    public private(set) var outcome: Outcome?
    public private(set) var submitting = false

    /// True from the moment an ad is watched until the gas lands, or the wait
    /// gives up. Drives the sheet's "Waiting for gas…" state.
    public private(set) var awaitingGas = false

    /// The action of the transaction in flight — "Send", "Register".
    ///
    /// Kept because `pending` is cleared the instant it is confirmed, and the
    /// waiting sheet still has to name what is being waited for. Android keeps
    /// it for the same reason.
    public private(set) var lastAction: String?

    private var build: ((EarthKey) throws -> [ProtoAny])?
    private var onSuccess: (() async -> Void)?

    public init() {}

    /// Ask for a transaction. Shows the confirmation sheet; nothing is signed
    /// until it is confirmed.
    ///
    /// `build` runs at confirm time and receives the key, so a sequence number
    /// cannot be baked in while the sheet sits open.
    public func request(
        _ details: Details,
        host: Host = .root,
        onSuccess: (() async -> Void)? = nil,
        build: @escaping (EarthKey) throws -> [ProtoAny]
    ) {
        self.build = build
        self.onSuccess = onSuccess
        self.host = host
        pending = details
    }

    public func cancel() {
        pending = nil
        build = nil
        onSuccess = nil
        host = .root
        awaitingGas = false
    }

    /// Waits for an ad grant to arrive, then lets the sheet notice.
    ///
    /// The reward callback fires when the *ad* finished, not when the gas
    /// lands: Google calls the backend, the backend sends from its hot wallet,
    /// and that send has to be included in a block. So a single balance read
    /// straight after the ad always runs too early — which is exactly how this
    /// failed on Android, where the grant arrived, the sheet never looked
    /// again, and the confirm button stayed behind "Watch an ad for gas" with
    /// nothing to say why.
    ///
    /// Reads the chain directly rather than going through the app model's
    /// refresh, so the check cannot race a refresh that has not landed yet.
    public func awaitGas(in model: AppModel) async {
        guard let needed = BigInt(pending?.feeUerth ?? ""), !model.address.isEmpty else { return }
        awaitingGas = true
        defer { awaitingGas = false }

        for _ in 0 ..< Self.gasPollAttempts {
            try? await Task.sleep(nanoseconds: Self.gasPollIntervalNanos)
            let raw = await model.client.balance(model.address, denom: Constants.gasDenom)
            if let now = BigInt(raw), now >= needed {
                // Bring the rest of the UI in line; the sheet reads its balance
                // from the model.
                await model.refresh()
                return
            }
        }
    }

    // The grant is a bank send, so it lands in a block. Roughly a minute of
    // patience against a ~6s block time, matching Android.
    private static let gasPollAttempts = 20
    private static let gasPollIntervalNanos: UInt64 = 3_000_000_000

    public func confirm(in model: AppModel) async {
        guard let details = pending, let build else { return }
        pending = nil
        lastAction = details.action
        submitting = true
        defer { submitting = false }

        do {
            let hash = try await broadcast(details: details, build: build, model: model)
            outcome = .succeeded(action: details.action, hash: hash)
            await onSuccess?()
            await model.refresh()
        } catch {
            outcome = .failed(action: details.action, reason: model.describe(error))
        }
        self.build = nil
        onSuccess = nil
    }

    private func broadcast(
        details: Details,
        build: @escaping (EarthKey) throws -> [ProtoAny],
        model: AppModel
    ) async throws -> String {
        // Decrypted at the moment of signing rather than kept resident. What
        // the session holds is the PIN, not the phrase — so a snapshot of the
        // app's memory between transactions has nothing to take.
        guard let pin = model.pin else { throw WalletStore.Error.notFound }
        let store = model.store
        let wallets = try await Task.detached { try store.unlock(pin: pin) }.value
        guard let wallet = wallets.first(where: { $0.address == model.address })
            ?? wallets.first
        else { throw WalletStore.Error.notFound }
        let key = try EarthKey(mnemonic: wallet.mnemonic)
        let messages = try build(key)
        return try await model.client.broadcast(
            messages,
            key: key,
            gasLimit: details.gasLimit,
            feeUerth: details.feeUerth
        )
    }

    public func dismissOutcome() {
        outcome = nil
        host = .root
    }
}
