import EarthCore
import Foundation

#if canImport(CoreNFC)
import CoreNFC
#endif

/// The chip dialogue, as the registration flow sees it: three MRZ fields in, a
/// DG1 and an EF.SOD out.
///
/// The dialogue itself is not here. It needs `NFCPassportReader`, whose package
/// declares no macOS floor while its OpenSSL dependency requires 10.15 — which
/// fails SwiftPM's platform check for this whole graph the moment macOS is in
/// it, and `EarthCore` puts it there so its checks stay runnable on a Mac. So
/// the reader is installed by the app shell, alongside the entitlement and the
/// Info.plist keys that only an app bundle can carry, exactly like
/// `PassportProving` and `AppOrientation`.
///
/// What stays here is what the flow needs to reason about: whether a read is
/// possible at all, and what each way of failing should say.
public enum PassportChip {

    /// Reads DG1 and EF.SOD off the chip. Installed by the app shell.
    public typealias Reader = (MRZ.Key) async throws -> PassportRegistration.Scan

    // Installed once at launch, before any screen exists, and never written
    // again — the same arrangement as `AppOrientation.setter` and for the same
    // reason: the app's `init` is not on an actor, so isolating this would only
    // move the problem to the call that fills it.
    nonisolated(unsafe) private static var reader: Reader?

    public static func install(_ reader: @escaping Reader) {
        Self.reader = reader
    }

    /// Why a read did not produce a scan.
    ///
    /// `wrongKey` is the one that matters. It is by far the most common
    /// failure and the only one the user can fix, so it has to be
    /// distinguishable — on Android it was not, and every mistyped document
    /// number sent people back to the chip to hold the passport flatter. The
    /// installed reader matches it by error *case* rather than by message for
    /// the same reason: the message says "Mutual authentication failed … SW =
    /// 0x6985" and names neither BAC nor the MRZ.
    public enum Failure: Error, Equatable {
        /// This device has no reader, the entitlement is missing, or no reader
        /// was installed. Not recoverable in the flow.
        case unavailable
        /// The chip refused the access key — the three fields do not match
        /// this document.
        case wrongKey
        /// The tag went away, or was never a passport.
        case unreadable
        /// The read succeeded but the chip did not hand over what the proof
        /// needs.
        case missingFile(String)
        /// The user closed the system sheet.
        case cancelled
        case other(String)
    }

    /// Whether a reader session can be opened at all: iOS 15+, iPhone 7 or
    /// later, the entitlement present, and a reader installed. Checked before
    /// the step is offered rather than after, so an iPad shows an explanation
    /// instead of a button that does nothing.
    public static var isAvailable: Bool {
        guard reader != nil else { return false }
        #if canImport(CoreNFC)
        return NFCTagReaderSession.readingAvailable
        #else
        return false
        #endif
    }

    /// Hold the passport to the phone and read it.
    ///
    /// The system sheet is the whole UI for this: iOS owns it, it is
    /// foreground-only, and nothing can be drawn over it — which is why the
    /// registration flow has a step whose only job is to start this and wait.
    /// The session closes before this returns, so proving afterwards does not
    /// need the passport still held there.
    public static func read(key: MRZ.Key) async throws -> PassportRegistration.Scan {
        guard let reader else { throw Failure.unavailable }
        return try await reader(key)
    }
}

public extension PassportChip.Failure {
    /// What to put on screen. Each one says what to do next, because every
    /// case here is reached with a passport in one hand and a phone in the
    /// other.
    var message: String {
        switch self {
        case .unavailable:
            return "This device cannot read passport chips. It needs an iPhone 7 or later."
        case .wrongKey:
            return "The chip refused those three fields. Check the document number, date of birth and date of expiry against the two lines at the bottom of the photo page."
        case .unreadable:
            return "Lost contact with the chip. Rest the passport flat against the top of the phone and hold it still."
        case let .missingFile(name):
            return "The chip did not hand over \(name), so there is nothing to prove from."
        case .cancelled:
            return "Reading was cancelled."
        case let .other(reason):
            return reason
        }
    }
}
