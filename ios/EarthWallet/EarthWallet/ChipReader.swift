import CoreNFC
import EarthCore
import EarthUI
import Foundation
import NFCPassportReader

/// The passport chip dialogue. Installed into `EarthUI`'s `PassportChip` seam
/// at launch.
///
/// It lives in the app target rather than in `EarthUI` for a packaging reason,
/// not a design one: `NFCPassportReader` declares no macOS floor while its
/// OpenSSL dependency requires 10.15, which fails SwiftPM's platform check for
/// any graph macOS is part of — and `EarthCore` keeps macOS in it so its checks
/// stay runnable on a Mac. Here there is no such graph, and this is where the
/// entitlement and the Info.plist keys live anyway.
///
/// Ports the half of `wallet/passport/PassportSession.kt` that talks to the
/// card. jmrtd there, `NFCPassportReader` here, for the same reason: BAC, PACE
/// and secure messaging are not worth reimplementing, and a mistake in them
/// looks like a passport that will not read rather than like a bug.
enum ChipReader {

    static func install() {
        PassportChip.install(read)
    }

    /// Only DG1 and EF.SOD are asked for.
    ///
    /// A full read pulls DG2 as well — the JPEG of the holder's face, tens of
    /// kilobytes over a link that manages a few KB a second — and nothing
    /// downstream wants it. The proof is over the SOD's signature and DG1's
    /// hash; the photo would only be a thing to keep safe.
    private static func read(key: MRZ.Key) async throws -> PassportRegistration.Scan {
        // Derived rather than concatenated from the fields: the filler padding
        // and the three check digits are the part that is easy to get wrong,
        // and this is the same string `MRZ.bacKeySeed` hashes — asserted
        // against ICAO's published worked example in corecheck.
        let mrzKey = try MRZ.keyMaterial(key)

        // Fresh for every attempt. A reader held across attempts can leave the
        // applet mid-authentication, and the next tap is then refused with the
        // same 0x6985 a mistyped MRZ produces — which makes a stale session and
        // a wrong key indistinguishable. Android learned this the same way.
        let reader = PassportReader()
        // Held to the end of the scope, not to its last use. The reader owns
        // the `NFCTagReaderSession` and is its delegate, so nothing else on
        // this side keeps it alive across the suspension — and ARC is free to
        // release a local after the call that used it. Losing it mid-read tears
        // the session down with the passport still against the phone.
        defer { withExtendedLifetime(reader) {} }

        let passport: NFCPassportModel
        do {
            passport = try await reader.readPassport(
                mrzKey: mrzKey,
                tags: [.DG1, .SOD],
                // Chip authentication proves the chip is not a clone, which
                // this flow does not rest on: the proof is over the issuing
                // country's signature, and a cloned chip carries a signature
                // over the same nullifier as the original, so it registers as
                // the same human either way. Skipped to match jmrtd on Android
                // and to keep the dialogue short.
                skipCA: true,
                // PACE is *not* skipped. A growing share of issues refuse BAC
                // outright, and the library falls back to BAC by itself when
                // PACE is unsupported or fails — so leaving it on costs a
                // couple of APDUs and buys the documents BAC alone cannot open.
                skipPACE: false
            )
        } catch let error as NFCPassportReaderError {
            throw translate(error)
        } catch {
            throw PassportChip.Failure.other(error.localizedDescription)
        }

        // `data`, not `body`: the whole file including its tag and length, as
        // read off the chip. That is what jmrtd's input stream yields on
        // Android, what the SOD's hashes are computed over, and what
        // `PassportInputs` walks. `body` would drop the header and every hash
        // in the witness would miss.
        guard let dg1 = passport.getDataGroup(.DG1).map({ Data($0.data) }) else {
            throw PassportChip.Failure.missingFile("DG1")
        }
        guard let sod = passport.getDataGroup(.SOD).map({ Data($0.data) }) else {
            throw PassportChip.Failure.missingFile("EF.SOD")
        }
        return PassportRegistration.Scan(dg1: dg1, efSOD: sod)
    }

    /// Matched by case, never by message.
    ///
    /// This is the exact trap the Android port fell into: the code there tested
    /// the message for "BAC", and jmrtd does not put that word in it — what it
    /// says is "Mutual authentication failed … SW = 0x6985". Every refused key
    /// fell through to a generic error, so the screen asked people to hold the
    /// passport flatter instead of sending them back to the three fields they
    /// had mistyped.
    private static func translate(_ error: NFCPassportReaderError) -> PassportChip.Failure {
        switch error {
        case .InvalidMRZKey:
            return .wrongKey
        // 0x6985, "conditions of use not satisfied", is what the applet answers
        // a mutual authentication it could not verify.
        case let .ResponseError(_, sw1, sw2) where sw1 == 0x69 && sw2 == 0x85:
            return .wrongKey
        case .UserCanceled:
            return .cancelled
        case .NFCNotSupported:
            return .unavailable
        case .NoConnectedTag, .ConnectionError, .TimeOutError, .TagNotValid, .MoreThanOneTagFound:
            return .unreadable
        case .DataGroupNotRead, .UnsupportedDataGroup:
            return .missingFile("a data group")
        default:
            return .other(error.errorDescription ?? "\(error)")
        }
    }
}
