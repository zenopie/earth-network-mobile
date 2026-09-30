import EarthCore
import Foundation
#if canImport(DeviceCheck) && os(iOS)
import DeviceCheck
#endif

/// Free gas, paid for with Apple's word that this is the real app on a real
/// device.
///
/// The half of `GasGrant` that needs an app bundle: App Attest. The backend
/// issues a challenge, the Secure Enclave signs over it, and Apple's
/// attestation — not anything the app claims — is what the backend checks
/// before it sends dust.
///
/// A fresh key every time, never stored. An App Attest key is only worth
/// keeping for later *assertions*, and there are none here: each grant is a
/// one-off attestation, and a persisted key would be state to lose on
/// reinstall for no benefit.
enum AppAttestGas {

    /// A failure phrased for the person holding the phone.
    struct Failure: Error {
        let message: String
    }

    /// Requests a grant for `address`. Returning means the backend accepted —
    /// the coins may still be on their way, so the caller polls the chain.
    static func request(for address: String) async throws {
        #if canImport(DeviceCheck) && os(iOS)
        let service = DCAppAttestService.shared
        // False on the Simulator and on devices without a Secure Enclave. Said
        // up front rather than left to fail inside `generateKey`, whose error
        // for this case reads as a bug.
        guard service.isSupported else { throw Failure(message: unsupported) }

        do {
            let challenge = try await GasGrant.challenge(for: address)
            guard let hash = GasGrant.clientDataHash(challenge: challenge, address: address) else {
                throw Failure(message: "The gas service sent an unreadable challenge. Try again.")
            }
            let keyId = try await service.generateKey()
            let attestation = try await service.attestKey(keyId, clientDataHash: hash)
            _ = try await GasGrant.submit(
                address: address,
                challenge: challenge,
                keyId: keyId,
                attestation: attestation
            )
        } catch let error as DCError {
            throw Failure(message: describe(error))
        } catch let refused as GasGrant.Refused {
            throw Failure(message: refused.message)
        } catch let failure as Failure {
            throw failure
        } catch let error as URLError {
            throw Failure(message: error.code == .timedOut
                ? "The gas service took too long to answer. Try again."
                : "Couldn't reach the gas service. Check your connection and try again.")
        } catch {
            throw Failure(message: "Couldn't get free gas right now. Try again shortly.")
        }
        #else
        throw Failure(message: unsupported)
        #endif
    }

    private static let unsupported =
        "This device can't verify the app, so free gas isn't available here."

    #if canImport(DeviceCheck) && os(iOS)
    /// DeviceCheck's own descriptions are written for developers ("The
    /// operation couldn't be completed. (com.apple.devicecheck.error error 2.)").
    private static func describe(_ error: DCError) -> String {
        switch error.code {
        case .featureUnsupported:
            return unsupported
        case .serverUnavailable:
            // Apple's attestation service, not ours — worth saying which, since
            // retrying our backend will not help.
            return "Apple's verification service is unavailable. Try again shortly."
        case .invalidKey, .invalidInput:
            return "App verification failed. Try again."
        default:
            return "Couldn't verify the app on this device. Try again."
        }
    }
    #endif
}
