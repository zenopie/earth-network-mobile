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
        onSuccess: (() async -> Void)? = nil,
        build: @escaping (EarthKey) throws -> [ProtoAny]
    ) {
        self.build = build
        self.onSuccess = onSuccess
        pending = details
    }

    public func cancel() {
        pending = nil
        build = nil
        onSuccess = nil
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
        // The phrase is read behind its own prompt here, at the moment of
        // signing — not cached when the app unlocked. The key lives for this
        // call and no longer, which is why `withKey` is not used: the broadcast
        // is async and the key has to outlive a synchronous closure.
        let wallets = try model.store.list(
            reason: "Sign this \(details.action.lowercased()) transaction")
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

    public func dismissOutcome() { outcome = nil }
}
