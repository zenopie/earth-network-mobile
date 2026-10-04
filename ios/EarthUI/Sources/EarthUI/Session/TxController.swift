import BigInt
import EarthCore
import Observation
import SwiftUI

/// One path for every transaction: confirm, broadcast, report.
///
/// Ports `ui/tx/TxController.kt`, and exists for the reason that one does:
/// before it, each screen broadcast on its own and reported the outcome in a
/// pair of toasts, so nobody could see what they were about to sign or read why
/// it failed. Keeping it in one place is also what will make the gas gate
/// universal — any transaction from an underfunded account can offer free
/// gas, not only registration.
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
        /// Sentences the user must read before confirming: a slash's cut of
        /// moved stake this tx settles, when moved stake can move again.
        public var notes: [String] = []
        public var gasLimit: UInt64 = TransactionSigner.defaultGasLimit

        /// Always `Fees.forGas(gasLimit)`. Derived rather than passed so the
        /// fee shown on the sheet and the fee broadcast cannot diverge — on
        /// Android they did, and claiming rewards (whose gas scales with the
        /// validator count) declared the flat default while broadcasting more.
        /// The sheet then reported the account funded when it was not.
        public var feeUerth: String { feeOverride.map(String.init) ?? Fees.forGas(gasLimit) }

        /// A private tx's fee as the chain priced it, when that was more than
        /// the estimate first shown: the sheet asks again at it (audit 3).
        public var feeOverride: UInt64?

        /// A private tx: unsigned, proven on the phone, its fee paid from a
        /// shielded ERTH note. The fee shown is an estimate (the exact figure
        /// comes from simulating at confirm time), and the balance it is
        /// checked against is shielded ERTH, not the account's.
        public var shielded = false

        /// Set only for registration, whose free gas is a shielded note (pc and
        /// its required 177-byte v2 ciphertext) paid
        /// against the registration itself: the backend checks this exact
        /// message the way the chain will, and shields the gas to `pcGas`.
        public var registration: (msg: MsgRegisterPrivate, pcGas: Data, ciphertextGas: Data)?

        public init(
            action: String,
            rows: [(String, String)],
            gasLimit: UInt64 = TransactionSigner.defaultGasLimit,
            shielded: Bool = false,
            registration: (msg: MsgRegisterPrivate, pcGas: Data, ciphertextGas: Data)? = nil
        ) {
            self.action = action
            self.rows = rows
            self.gasLimit = gasLimit
            self.shielded = shielded
            self.registration = registration
        }

        /// A private action's sheet: its fee estimated from the private gas estimate.
        public static func `private`(action: String, rows: [(String, String)], notes: [String] = [], gas: UInt64 = PrivacyWallet.privateGasEstimate,
                                     registration: (msg: MsgRegisterPrivate, pcGas: Data, ciphertextGas: Data)? = nil) -> Details {
            var d = Details(action: action, rows: rows, gasLimit: gas, shielded: true, registration: registration)
            d.notes = notes
            return d
        }
    }

    public enum Outcome: Identifiable {
        case succeeded(action: String, hash: String)
        /// Accepted by the node but not seen in a block before the wait ran
        /// out. It may still land or be dropped, so it is reported as neither
        /// — calling it a success is how a dropped send read as sent.
        case unconfirmed(action: String, hash: String)
        case failed(action: String, reason: String)

        public var id: String {
            switch self {
            case let .succeeded(_, hash): hash
            case let .unconfirmed(_, hash): "unconfirmed" + hash
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
        /// The shielded-notes screen, another sheet over the settings sheet.
        case notes
        /// The handle screen, another sheet over the settings sheet.
        case handle
        /// The identity switch, a sheet over the identity screen.
        case switchIdentity
    }

    public private(set) var host: Host = .root

    /// What is waiting on the confirmation sheet, if anything.
    public private(set) var pending: Details?
    /// What came back, if anything.
    public private(set) var outcome: Outcome?
    public private(set) var submitting = false

    /// True while the grant is being asked for and the backend has not
    /// answered. Keeps a second tap from spending a second grant.
    public private(set) var requestingGas = false

    /// True from the moment the backend accepts a grant until the gas lands,
    /// or the wait gives up. Drives the sheet's "Waiting for gas…" state.
    public private(set) var awaitingGas = false

    /// Why the last grant was refused, in the backend's words. Cleared on the
    /// next attempt.
    public private(set) var gasError: String?

    /// The gas request's proof of work, 0...1, while it is being made.
    public private(set) var gasWork: Double?

    /// The action of the transaction in flight — "Send", "Register".
    ///
    /// Kept because `pending` is cleared the instant it is confirmed, and the
    /// waiting sheet still has to name what is being waited for. Android keeps
    /// it for the same reason.
    public private(set) var lastAction: String?

    private var build: ((EarthKey) async throws -> [ProtoAny])?
    private var runPrivate: ((PrivacyWallet) async throws -> TxResult)?
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
        build: @escaping (EarthKey) async throws -> [ProtoAny]
    ) {
        self.build = build
        self.runPrivate = nil
        self.onSuccess = onSuccess
        self.host = host
        pending = details
    }

    /// Ask for a private transaction: unsigned, proven on the phone, its fee
    /// paid from a shielded ERTH note. `run` proves and broadcasts (a
    /// PrivacyWallet action) once confirmed.
    public func requestPrivate(
        _ details: Details,
        host: Host = .root,
        onSuccess: (() async -> Void)? = nil,
        run: @escaping (PrivacyWallet) async throws -> TxResult
    ) {
        var d = details
        d.shielded = true
        self.build = nil
        self.runPrivate = run
        self.onSuccess = onSuccess
        self.host = host
        pending = d
    }

    /// Reports a failure that happened before any sheet (a read the action needed, a quote): nothing was sent.
    public func showFailure(_ action: String, _ error: Swift.Error, model: AppModel) {
        outcome = .failed(action: action, reason: model.describe(error))
    }

    public func cancel() {
        pending = nil
        build = nil
        runPrivate = nil
        onSuccess = nil
        host = .root
        awaitingGas = false
        gasError = nil
    }

    /// Asks the backend for free gas, then waits for it to land.
    ///
    /// One grant exists: a registration's, a shielded note paid against the
    /// registration itself (the backend checks it as the chain will) to a pc
    /// of our own with its v2 ciphertext, so its spend is unlinkable. ERTH for
    /// a signed tx's fee comes from unshielding. A 202 is treated like a 200:
    /// the chain is the only authority on arrival.
    public func requestGas(in model: AppModel) async {
        guard !requestingGas, !awaitingGas, !model.address.isEmpty, let details = pending else { return }
        requestingGas = true
        gasError = nil
        do {
            if let reg = details.registration {
                defer { gasWork = nil }
                _ = try await GasGrant.request(.register(reg.msg, pcGas: reg.pcGas, ciphertextGas: reg.ciphertextGas)) { p in
                    Task { @MainActor [weak self] in if self?.requestingGas == true { self?.gasWork = p } }
                }
            } else if details.shielded {
                throw GasGrant.Refused(status: 0, message: "Fees for private actions are paid from shielded ERTH: your registration reward, or ERTH sent to your shielded address.")
            } else {
                throw GasGrant.Refused(status: 0, message: "Need ERTH in your public account for fees? Unshield some from Portfolio.")
            }
        } catch {
            requestingGas = false
            gasError = Self.describeGasFailure(error)
            return
        }
        requestingGas = false
        await awaitGas(in: model)
    }

    private static func describeGasFailure(_ error: Error) -> String {
        if let refused = error as? GasGrant.Refused { return refused.message }
        if let url = error as? URLError {
            return url.code == .timedOut
                ? "The gas service took too long to answer. Try again."
                : "Couldn't reach the gas service. Check your connection and try again."
        }
        if let l = error as? LocalizedError, let d = l.errorDescription { return d }
        return "Couldn't get free gas right now. Try again shortly."
    }

    /// Waits for a gas grant to arrive, then lets the sheet notice: the
    /// backend answering is not the gas landing. A shielded grant is found by
    /// syncing the note streams; a transparent one by the account balance.
    public func awaitGas(in model: AppModel) async {
        guard let details = pending, let needed = UInt64(details.feeUerth), !model.address.isEmpty else { return }
        awaitingGas = true
        defer { awaitingGas = false }

        for _ in 0 ..< Self.gasPollAttempts {
            try? await Task.sleep(nanoseconds: Self.gasPollIntervalNanos)
            if details.shielded {
                await model.syncPrivacy()
                if model.shieldedErth >= needed { return }
            } else {
                let raw = await model.client.balance(model.address, denom: Constants.gasDenom)
                if let now = BigInt(raw), now >= BigInt(needed) {
                    await model.refresh()
                    return
                }
            }
        }
    }

    // The grant is a bank send, so it lands in a block. Roughly a minute of
    // patience against a ~6s block time, matching Android.
    private static let gasPollAttempts = 20
    private static let gasPollIntervalNanos: UInt64 = 3_000_000_000

    public func confirm(in model: AppModel) async {
        guard let details = pending, build != nil || runPrivate != nil else { return }
        let build = self.build
        let runPrivate = self.runPrivate
        // Audit 6 (I2): taken now, so what `onSuccess` queues (a chained
        // request, like the caretaker move after a handle move) is not
        // cleared when this one finishes.
        let onSuccess = self.onSuccess
        self.build = nil; self.runPrivate = nil; self.onSuccess = nil
        pending = nil
        lastAction = details.action
        submitting = true
        defer { submitting = false }

        do {
            let hash: String
            if let runPrivate {
                guard let w = model.privacy else { throw WalletStore.Error.notFound }
                // The fee the sheet showed bounds what the private run may pay (audit 3).
                hash = try await PrivacyWallet.$shownFee.withValue(UInt64(details.feeUerth)) { try await runPrivate(w).hash }
                model.publishPrivacy()
            } else {
                hash = try await broadcast(details: details, build: build!, model: model)
            }
            outcome = .succeeded(action: details.action, hash: hash)
            await onSuccess?()
            await model.refresh()
        } catch let e as PrivateTxEngine.FeeAboveQuote {
            // Nothing was proven or sent: show the sheet again at the chain's fee.
            var again = details
            again.feeOverride = e.fee
            self.build = build; self.runPrivate = runPrivate; self.onSuccess = onSuccess
            pending = again
            return
        } catch let EarthClient.Error.notCommitted(hash) {
            // Not `onSuccess`: that clears the form, and the user may need what
            // they typed if this never lands.
            outcome = .unconfirmed(action: details.action, hash: hash)
            await model.refresh()
        } catch {
            outcome = .failed(action: details.action, reason: model.describe(error))
        }
    }

    private func broadcast(
        details: Details,
        build: @escaping (EarthKey) async throws -> [ProtoAny],
        model: AppModel
    ) async throws -> String {
        // Decrypted again at the moment of signing. (Audit 6, K7: the
        // unlocked session does hold the phrases, in AppModel.wallets; this
        // read keeps signing on the sealed copy, not on that list.)
        guard let pin = model.pin else { throw WalletStore.Error.notFound }
        let store = model.store
        let wallets = try await Task.detached { try store.unlock(pin: pin) }.value
        guard let wallet = wallets.first(where: { $0.address == model.address })
            ?? wallets.first
        else { throw WalletStore.Error.notFound }
        let key = try EarthKey(mnemonic: wallet.mnemonic)
        let messages = try await build(key)
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
