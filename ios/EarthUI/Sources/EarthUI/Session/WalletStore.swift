import EarthCore
import Foundation
import LocalAuthentication
import Security

/// Where the recovery phrase lives.
///
/// The Keychain, behind biometrics or the device passcode, and nothing else:
/// no file, no `UserDefaults`, no copy in memory beyond the moment a signature
/// needs it. The Android side has to build most of this — an encrypted
/// preferences file, a PIN, an attempt counter — because Android has no single
/// place with the same guarantee. iOS does, so this is thin on purpose.
///
/// `.whenPasscodeSetThisDeviceOnly` is the strict end of the accessibility
/// scale on purpose: the phrase never leaves this device, never lands in an
/// iCloud or iTunes backup, and the entry is destroyed if the passcode is
/// removed. Losing the phrase with the passcode is the correct outcome — a
/// wallet whose phrase survives on a device with no lock is not self-custody.
public struct WalletStore {

    public enum Error: Swift.Error, Equatable {
        case notFound
        case authenticationFailed
        case keychain(OSStatus)
        case invalidMnemonic
    }

    private static let service = "network.erth.wallet"
    private static let account = "mnemonic"

    public init() {}

    /// Whether a wallet exists, without prompting for anything.
    ///
    /// Asked at launch to decide between the setup flow and the lock screen,
    /// so it must not raise a Face ID sheet. `interactionNotAllowed` is the
    /// answer it will usually get, and it means the item is there and simply
    /// will not be handed over without the user present — which is a yes.
    public var exists: Bool {
        let context = LAContext()
        context.interactionNotAllowed = true

        var query = Self.baseQuery
        query[kSecUseAuthenticationContext as String] = context

        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    /// Store a phrase, replacing anything already there.
    public func save(mnemonic: String) throws {
        guard BIP39.isValid(mnemonic: mnemonic) else { throw Error.invalidMnemonic }

        var control: SecAccessControl?
        var error: Unmanaged<CFError>?
        control = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            .userPresence,
            &error
        )
        guard let control else { throw Error.keychain(errSecParam) }

        SecItemDelete(Self.baseQuery as CFDictionary)

        var attributes = Self.baseQuery
        attributes[kSecValueData as String] = Data(mnemonic.utf8)
        attributes[kSecAttrAccessControl as String] = control

        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw Error.keychain(status) }
    }

    /// Read the phrase back. Prompts for Face ID, Touch ID, or the passcode.
    ///
    /// `reason` is what the system sheet shows, so it says what the phrase is
    /// about to be used for rather than a generic "authenticate".
    public func mnemonic(reason: String) throws -> String {
        let context = LAContext()
        context.localizedReason = reason

        var query = Self.baseQuery
        query[kSecReturnData as String] = true
        query[kSecUseAuthenticationContext as String] = context

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data, let mnemonic = String(data: data, encoding: .utf8) else {
                throw Error.notFound
            }
            return mnemonic
        case errSecItemNotFound:
            throw Error.notFound
        case errSecUserCanceled, errSecAuthFailed:
            throw Error.authenticationFailed
        default:
            throw Error.keychain(status)
        }
    }

    /// Derive the signing key, hold it only as long as `body` runs.
    ///
    /// Every signature goes through here rather than caching a key on a view
    /// model, so an unlocked app that is left on a table still cannot sign
    /// without the user present.
    public func withKey<T>(reason: String, _ body: (EarthKey) throws -> T) throws -> T {
        let phrase = try mnemonic(reason: reason)
        return try body(try EarthKey(mnemonic: phrase))
    }

    public func delete() {
        SecItemDelete(Self.baseQuery as CFDictionary)
    }

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
