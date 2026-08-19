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
    }

    private static let service = "network.erth.wallet"
    private static let account = "mnemonic"

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

    /// Store a phrase, replacing everything already there.
    ///
    /// The single-wallet entry point, kept for first run. Adding to an
    /// existing list goes through `add`.
    public func save(mnemonic: String, name: String = "Wallet 1") throws {
        try write([Entry(name: name, mnemonic: mnemonic, address: try EarthKey(mnemonic: mnemonic).address)])
    }

    /// Add a wallet and return its index.
    public func add(mnemonic: String, name: String) throws -> Int {
        var wallets = try list(reason: "Add a wallet")
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
        try write(wallets)
        return wallets.count - 1
    }

    /// Every wallet held. Prompts once, not once per wallet.
    public func list(reason: String) throws -> [Entry] {
        guard exists else { return [] }
        let data = Data(try mnemonic(reason: reason).utf8)
        // Written as JSON since the second wallet; a bare phrase is what the
        // first release stored, and it still has to open.
        if let wallets = try? JSONDecoder().decode([Entry].self, from: data) {
            return wallets
        }
        let phrase = String(decoding: data, as: UTF8.self)
        guard let address = try? EarthKey(mnemonic: phrase).address else { return [] }
        return [Entry(name: "Wallet 1", mnemonic: phrase, address: address)]
    }

    private func write(_ wallets: [Entry]) throws {
        for wallet in wallets where !BIP39.isValid(mnemonic: wallet.mnemonic) {
            throw Error.invalidMnemonic
        }
        let encoded = try JSONEncoder().encode(wallets)
        try store(String(decoding: encoded, as: UTF8.self))
    }

    private func store(_ mnemonic: String) throws {

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
        attributes[kSecValueData as String] = Data(mnemonic.utf8)
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
