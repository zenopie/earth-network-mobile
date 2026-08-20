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
        public var feeUerth: String = TransactionSigner.defaultFeeUerth

        public init(
            action: String,
            rows: [(String, String)],
            gasLimit: UInt64 = TransactionSigner.defaultGasLimit,
            feeUerth: String = TransactionSigner.defaultFeeUerth
        ) {
            self.action = action
            self.rows = rows
            self.gasLimit = gasLimit
            self.feeUerth = feeUerth
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
        /// itself and is the app's only two-sheet-deep flow.
        case allocation
    }

    public private(set) var host: Host = .root

    /// What is waiting on the confirmation sheet, if anything.
    public private(set) var pending: Details?
    /// What came back, if anything.
    public private(set) var outcome: Outcome?
    public private(set) var submitting = false

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
    }

    public func confirm(in model: AppModel) async {
        guard let details = pending, let build else { return }
        pending = nil
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
