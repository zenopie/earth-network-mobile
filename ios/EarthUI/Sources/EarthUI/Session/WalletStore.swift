import CryptoKit
import EarthCore
import Foundation
import LocalAuthentication
import Security

/// Where the recovery phrase lives.
///
/// Two locks, not one. The wallet list is encrypted under a key derived from
/// the user's PIN, and the ciphertext is kept in the Keychain — so the device
/// protects it at rest and the PIN protects it in use, which is the shape the
/// Android app has. The PIN is not a screen in front of an already-readable
/// secret; without it the bytes are meaningless.
///
/// `.whenPasscodeSetThisDeviceOnly` is the strict end of the accessibility
/// scale on purpose: the ciphertext never leaves this device, never lands in
/// an iCloud or iTunes backup, and the entry is destroyed if the passcode is
/// removed.
///
/// The PIN itself is never stored. Whether one is right is answered by
/// whether the decryption authenticates — AES-GCM fails closed on a wrong key,
/// so there is no separate hash to compare and nothing to steal that would
/// confirm a guess offline.
public struct WalletStore: Sendable {

    /// One wallet in the list.
    public struct Entry: Codable, Equatable, Identifiable {
        public let name: String
        public let mnemonic: String
        /// Kept alongside so the list can be shown without deriving every key,
        /// which would mean a biometric prompt per row.
        public let address: String

        public var id: String { address }
    }

    public enum Error: Swift.Error, Equatable {
        case notFound
        case authenticationFailed
        case keychain(OSStatus)
        case invalidMnemonic
        /// The device has no passcode, so there is nothing to protect the
        /// phrase with and the Keychain refuses to store it.
        ///
        /// Not a failure to work around: an entry that survives on a device
        /// with no lock is not self-custody. It is also the state every fresh
        /// simulator is in.
        case noDeviceLock
        /// The PIN did not decrypt the wallet.
        case wrongPin
        case corrupt
    }

    /// How this wallet is opened.
    ///
    /// The vault is always sealed under a secret; this only says where the
    /// secret comes from. `.biometrics` has no PIN to remember, so it seals
    /// under a random 32-byte key that only the biometric prompt can retrieve —
    /// stronger
    /// than four digits, and unrecoverable if the Keychain entry goes with the
    /// passcode. `.both` seals under the PIN and *also* keeps it behind
    /// biometrics, so either opens it.
    public enum Method: String, CaseIterable, Sendable {
        case pin, biometrics, both

        public var usesPin: Bool { self != .biometrics }
        public var usesBiometrics: Bool { self != .pin }
    }

    /// The stored blob: a salt and the sealed wallet list.
    private struct Vault: Codable {
        let salt: Data
        let sealed: Data
    }

    private static let service = "network.erth.wallet"
    private static let account = "mnemonic"
    /// The vault's secret, kept behind biometrics. A separate item because it
    /// has a different access control: this one demands the user, the vault
    /// itself only demands the device.
    private static let biometricAccount = "unlock-secret"

    // A simulator has no passcode and no enrolled biometrics, so requiring
    // user presence there makes the app impossible to run past its first
    // screen — every wallet write fails and no other screen is reachable.
    //
    // `targetEnvironment(simulator)` is compiled out of every device build, so
    // this cannot weaken a shipped app. The device keeps both halves: the
    // phrase is destroyed with the passcode, and each read needs the user.
    #if targetEnvironment(simulator)
    private static let accessibility = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    private static let flags: SecAccessControlCreateFlags = []
    #else
    private static let accessibility = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
    private static let flags: SecAccessControlCreateFlags = .userPresence
    #endif

    public init() {}

    /// Whether a wallet exists, without prompting for anything.
    ///
    /// Asked at launch to decide between the setup flow and the lock screen,
    /// so it must not raise an authentication sheet. `interactionNotAllowed` is the
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

    /// Create the vault with a first wallet.
    public func create(mnemonic: String, name: String, pin: String) throws {
        try write(
            [Entry(name: name, mnemonic: mnemonic, address: try EarthKey(mnemonic: mnemonic).address)],
            pin: pin
        )
    }

    /// Add a wallet and return its index.
    public func add(mnemonic: String, name: String, pin: String) throws -> Int {
        var wallets = try unlock(pin: pin)
        let entry = Entry(
            name: name,
            mnemonic: mnemonic,
            address: try EarthKey(mnemonic: mnemonic).address
        )
        // Importing a phrase already held is a no-op rather than a duplicate
        // row that switching between would do nothing.
        if let existing = wallets.firstIndex(where: { $0.address == entry.address }) {
            return existing
        }
        wallets.append(entry)
        try write(wallets, pin: pin)
        return wallets.count - 1
    }

    /// Every wallet held, given the PIN.
    ///
    /// A wrong PIN surfaces as `wrongPin` rather than as garbage, because
    /// AES-GCM authenticates: it refuses to produce plaintext it cannot vouch
    /// for instead of returning noise that would parse as an empty list.
    public func unlock(pin: String) throws -> [Entry] {
        guard let stored = try read() else { throw Error.notFound }
        guard let vault = try? JSONDecoder().decode(Vault.self, from: stored) else {
            throw Error.corrupt
        }
        let key = Self.key(pin: pin, salt: vault.salt)
        guard let box = try? AES.GCM.SealedBox(combined: vault.sealed),
              let opened = try? AES.GCM.open(box, using: key)
        else { throw Error.wrongPin }
        guard let wallets = try? JSONDecoder().decode([Entry].self, from: opened) else {
            throw Error.corrupt
        }
        return wallets
    }

    private func write(_ wallets: [Entry], pin: String) throws {
        for wallet in wallets where !BIP39.isValid(mnemonic: wallet.mnemonic) {
            throw Error.invalidMnemonic
        }
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        guard status == errSecSuccess else { throw Error.keychain(status) }

        let sealed = try AES.GCM.seal(
            try JSONEncoder().encode(wallets),
            using: Self.key(pin: pin, salt: salt)
        )
        guard let combined = sealed.combined else { throw Error.corrupt }
        try store(try JSONEncoder().encode(Vault(salt: salt, sealed: combined)))
    }

    /// The PIN stretched into a key.
    ///
    /// 200,000 rounds because the secret is four digits: the whole keyspace is
    /// 10,000, so the only thing standing between a stolen ciphertext and the
    /// phrase is how long each guess takes. The on-device lockout protects the
    /// screen; this protects the bytes if they ever leave the device.
    private static func key(pin: String, salt: Data) -> SymmetricKey {
        let stretched = Hashes.pbkdf2SHA512(
            password: Data(pin.utf8),
            salt: salt,
            rounds: 200_000,
            keyLength: 32
        )
        return SymmetricKey(data: stretched)
    }

    private func store(_ payload: Data) throws {

        var error: Unmanaged<CFError>?
        let control = SecAccessControlCreateWithFlags(
            nil,
            Self.accessibility,
            Self.flags,
            &error
        )
        // Creating the access control is itself what fails first on a device
        // with no passcode.
        guard let control else { throw Error.noDeviceLock }

        SecItemDelete(Self.baseQuery as CFDictionary)

        var attributes = Self.baseQuery
        attributes[kSecValueData as String] = payload
        attributes[kSecAttrAccessControl as String] = control

        let status = SecItemAdd(attributes as CFDictionary, nil)
        switch status {
        case errSecSuccess:
            return
        // -25308. What a device with no passcode returns for an item that
        // requires one.
        case errSecInteractionNotAllowed:
            throw Error.noDeviceLock
        default:
            throw Error.keychain(status)
        }
    }

    // MARK: - biometrics

    /// Whether this device can do biometrics at all.
    public static var biometricsAvailable: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics, error: &error
        )
    }

    public static var biometryName: String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return switch context.biometryType {
        case .faceID: "Face ID"
        case .touchID: "Touch ID"
        case .opticID: "Optic ID"
        // Reached when the hardware is unknown to this SDK or nothing is
        // enrolled. Lowercase because it only ever appears mid-sentence, where
        // a capitalised generic reads like a product that does not exist.
        default: "biometrics"
        }
    }

    /// Whether a secret is held behind biometrics, without asking for it.
    public var biometricsEnrolled: Bool {
        let context = LAContext()
        context.interactionNotAllowed = true
        var query = Self.biometricQuery
        query[kSecUseAuthenticationContext as String] = context
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    /// Put the vault's secret behind biometrics.
    public func enrollBiometrics(secret: String) throws {
        var error: Unmanaged<CFError>?
        let control = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            .userPresence,
            &error
        )
        guard let control else { throw Error.noDeviceLock }

        SecItemDelete(Self.biometricQuery as CFDictionary)
        var attributes = Self.biometricQuery
        attributes[kSecValueData as String] = Data(secret.utf8)
        attributes[kSecAttrAccessControl as String] = control

        let status = SecItemAdd(attributes as CFDictionary, nil)
        switch status {
        case errSecSuccess: return
        case errSecInteractionNotAllowed: throw Error.noDeviceLock
        default: throw Error.keychain(status)
        }
    }

    public func forgetBiometrics() {
        SecItemDelete(Self.biometricQuery as CFDictionary)
    }

    /// Ask for the secret. Prompts.
    public func biometricSecret(reason: String) throws -> String {
        let context = LAContext()
        context.localizedReason = reason

        var query = Self.biometricQuery
        query[kSecReturnData as String] = true
        query[kSecUseAuthenticationContext as String] = context

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data, let secret = String(data: data, encoding: .utf8) else {
                throw Error.notFound
            }
            return secret
        case errSecItemNotFound: throw Error.notFound
        case errSecUserCanceled, errSecAuthFailed: throw Error.authenticationFailed
        default: throw Error.keychain(status)
        }
    }

    /// A secret for a wallet with no PIN. 32 random bytes, so the thing
    /// standing behind the biometric prompt is a real key rather than four
    /// digits.
    public static func generatedSecret() -> String {
        var bytes = Data(count: 32)
        _ = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        return bytes.base64EncodedString()
    }

    private static var biometricQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: biometricAccount,
        ]
    }

    /// Re-seal the vault under a new secret, keeping the wallets.
    public func reseal(from old: String, to new: String) throws {
        let wallets = try unlock(pin: old)
        try write(wallets, pin: new)
    }

    private func read() throws -> Data? {
        var query = Self.baseQuery
        query[kSecReturnData as String] = true

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess: return item as? Data
        case errSecItemNotFound: return nil
        default: throw Error.keychain(status)
        }
    }

    public func delete() {
        SecItemDelete(Self.baseQuery as CFDictionary)
        SecItemDelete(Self.biometricQuery as CFDictionary)
    }

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
