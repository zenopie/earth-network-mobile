import Foundation

/// Which transaction the confirm sheet may show, and when.
///
/// The UI's TxController keeps its sheets in this, so the rules live where a
/// test can reach them: a request's sheet is shown once, a confirm sends it
/// once, and a sheet for a request that is being sent or was sent can never
/// come back. The one exception is a re-ask (`reask`): the chain priced the
/// tx above what the sheet showed, and nothing was proven or sent, so the
/// same request is asked again at the new fee. As Android's TxGate.
///
/// Build 21 showed why this has to be one rule rather than care at each call
/// site: a registration's sheet came back over "Sending" and was confirmed a
/// second time.
public struct TxGate: Sendable, Equatable {
    public enum Phase: Sendable, Equatable {
        case idle
        /// The sheet for this request is up.
        case confirming(UUID)
        /// This request was confirmed and is being proven, sent or waited on.
        case sending(UUID)
    }

    public private(set) var phase: Phase = .idle
    /// Requests that reached a send. Never shown again.
    private var spent: Set<UUID> = []

    public init() {}

    /// A request's sheet may be shown: not while another tx is being sent
    /// (its sheet would draw over "Sending" and could be confirmed into a
    /// second broadcast), and never for a request already sent.
    public mutating func present(_ id: UUID) -> Bool {
        if case .sending = phase { return false }
        guard !spent.contains(id) else { return false }
        phase = .confirming(id)
        return true
    }

    /// The sheet's Confirm: only for the request on screen, and only once.
    public mutating func confirm(_ id: UUID) -> Bool {
        guard phase == .confirming(id) else { return false }
        phase = .sending(id)
        spent.insert(id)
        return true
    }

    /// The chain asks a higher fee than the sheet showed and nothing was
    /// sent: the same request goes back on screen at it.
    public mutating func reask(_ id: UUID) -> Bool {
        guard phase == .sending(id) else { return false }
        spent.remove(id)
        phase = .confirming(id)
        return true
    }

    /// The send settled (landed, failed, or its outcome is unknown).
    public mutating func finish(_ id: UUID) {
        if phase == .sending(id) { phase = .idle }
    }

    /// The sheet was dismissed. A send in flight is not cancelled by it.
    public mutating func cancel() {
        if case .confirming = phase { phase = .idle }
    }

    /// Whether `id`'s sheet is the one up. A gas wait started from a sheet
    /// acts only while it is: once confirmed, cancelled or replaced, it stops.
    public func showing(_ id: UUID) -> Bool { phase == .confirming(id) }

    public var sending: Bool { if case .sending = phase { true } else { false } }

    /// The sentence a re-asked sheet carries, so it does not read as the
    /// same request popping up again.
    public static func reaskNote(fee: UInt64, shown: UInt64) -> String {
        "Nothing was sent. The network's fee for this is \(Token.erth.format(String(fee))) ERTH, "
            + "more than the \(Token.erth.format(String(shown))) ERTH estimated. Confirm again to send it at that fee."
    }

    /// The chain's refusals of a registration it already holds: this
    /// identity registered (1130), this passport already registered to this
    /// identity (1123), or this exact registration used (1124). For a wallet
    /// whose registration then turns out live, a duplicate of one that landed.
    public static let duplicateRegistrationCodes: Set<Int> = [1123, 1124, 1130]

    public static func duplicateRegistration(_ error: Swift.Error) -> Bool {
        if error is PrivacyWallet.IdentityUsed { return true }
        guard let r = error as? UnsignedTx.TxRejected else { return false }
        return r.codespace == PrivacyWallet.identityUsedCodespace && duplicateRegistrationCodes.contains(r.code)
    }
}
