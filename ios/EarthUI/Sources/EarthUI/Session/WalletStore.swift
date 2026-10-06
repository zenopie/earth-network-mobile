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
    public struct Entry: Codable, Equatable, Identifiable, Sendable {
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
        /// The secret behind the biometric prompt is gone because the enrolled
        /// faces or fingers changed. Deliberate — see `stageBiometrics` — and
        /// permanent: the vault was sealed under that secret, so only the
        /// recovery phrase gets back in.
        case biometricsInvalidated
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
    /// passcode. `.both` seals under the PIN combined with a random half kept
    /// behind biometrics (`combine`), so both are needed every time and
    /// neither opens it alone.
    public enum Method: String, CaseIterable, Sendable {
        case pin, biometrics, both

        public var usesPin: Bool { self != .biometrics }
        public var usesBiometrics: Bool { self != .pin }
    }

    /// The stored blob: a salt, the sealed payload, and (format 2) the key
    /// derivation that sealed it, so its parameters can change without
    /// stranding a vault sealed under the old ones. A format-1 blob has no
    /// `kdf` and was sealed at `KDF.legacy`.
    private struct Vault: Codable {
        let salt: Data
        let sealed: Data
        var format: Int?
        var kdf: String?
        var rounds: Int?
    }

    /// The vault format this build writes; a newer one is refused.
    private static let format = 2

    /// How the unlock secret is stretched into the vault key.
    public struct KDF: Equatable, Sendable {
        public let id: String
        public let rounds: Int

        static let pbkdf2SHA512 = "pbkdf2-hmac-sha512"
        /// 200,000 rounds of PBKDF2-HMAC-SHA512: OWASP's figure for that hash
        /// (Android's 600,000 is the SHA-256 one).
        public static let current = KDF(id: pbkdf2SHA512, rounds: 200_000)
        /// What a vault from before the parameters were recorded was sealed with.
        static let legacy = KDF(id: pbkdf2SHA512, rounds: 200_000)

        /// A known derivation at a sane count: a ceiling so a damaged blob
        /// cannot hang the unlock, a floor so nothing is accepted at a trivial one.
        var supported: Bool { id == Self.pbkdf2SHA512 && (100_000 ... 10_000_000).contains(rounds) }
    }

    /// What the vault seals (format 2): the wallets, and the key every
    /// wallet's private data store is sealed with. A format-1 vault sealed
    /// the bare wallet list.
    private struct Payload: Codable {
        let wallets: [Entry]
        let dataKey: Data
    }

    /// An opened vault.
    public struct Opened: Sendable {
        public let wallets: [Entry]
        /// The private data stores' key (PrivacyStore / StateSeal): inside the
        /// vault, so it opens exactly when the wallet does, whatever the
        /// unlock method, and survives a change of method.
        public let dataKey: Data
    }

    private static let service = "network.erth.wallet"
    private static let account = "mnemonic"
    /// The vault's secret, kept behind biometrics. A separate item because it
    /// has a different access control: this one demands the user, the vault
    /// itself only demands the device.
    private static let biometricAccount = "unlock-secret"

    /// The vault is bound to this device and nothing more.
    ///
    /// It does not demand user presence: then *reading* it would raise a
    /// biometric prompt — on a two-factor wallet a second prompt on top of the
    /// one fetching the key half, and on a PIN-only wallet a biometric prompt
    /// for a wallet that asked not to use biometrics at all.
    ///
    /// The contents are sealed, so the Keychain's job here is at-rest binding:
    /// never leaves the device, never in a backup, destroyed with the passcode.
    /// What demands the user is the half stored under `biometricAccount`.
    private static let accessibility = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly

    public init() {}

    /// Whether a wallet exists.
    ///
    /// Asked at launch to decide between the setup flow and the lock screen.
    /// A plain lookup: the vault does not demand the user, and reading it
    /// gives ciphertext, which is no use to anyone without the secret.
    public var exists: Bool {
        SecItemCopyMatching(Self.baseQuery as CFDictionary, nil) == errSecSuccess
    }

    /// Create the vault with a first wallet.
    public func create(mnemonic: String, name: String, pin: String) throws {
        try write(
            [Entry(name: name, mnemonic: mnemonic, address: try EarthKey(mnemonic: mnemonic).address)],
            dataKey: Self.newDataKey(),
            pin: pin
        )
    }

    /// Add a wallet and return its index.
    public func add(mnemonic: String, name: String, pin: String) throws -> Int {
        let opened = try open(pin: pin)
        var wallets = opened.wallets
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
        try write(wallets, dataKey: opened.dataKey, pin: pin)
        return wallets.count - 1
    }

    /// Every wallet held, given the PIN.
    ///
    /// A wrong PIN surfaces as `wrongPin` rather than as garbage, because
    /// AES-GCM authenticates: it refuses to produce plaintext it cannot vouch
    /// for instead of returning noise that would parse as an empty list.
    public func unlock(pin: String) throws -> [Entry] {
        try open(pin: pin).wallets
    }

    /// The wallets and the private data key, given the PIN. A vault from
    /// before the key existed gets one now (written before it is returned,
    /// so nothing is ever sealed under a key that is not on disk); one sealed
    /// at older derivation parameters is re-sealed at the current ones
    /// (best effort: it opens either way).
    public func open(pin: String) throws -> Opened {
        guard let stored = try read() else { throw Error.notFound }
        guard let vault = try? JSONDecoder().decode(Vault.self, from: stored), (vault.format ?? 1) <= Self.format else {
            throw Error.corrupt
        }
        let kdf = vault.kdf.map { KDF(id: $0, rounds: vault.rounds ?? 0) } ?? .legacy
        guard kdf.supported else { throw Error.corrupt }
        let key = Self.key(pin: pin, salt: vault.salt, kdf: kdf)
        guard let box = try? AES.GCM.SealedBox(combined: vault.sealed),
              let opened = try? AES.GCM.open(box, using: key)
        else { throw Error.wrongPin }
        if let payload = try? JSONDecoder().decode(Payload.self, from: opened), payload.dataKey.count == 32 {
            if kdf != .current { try? write(payload.wallets, dataKey: payload.dataKey, pin: pin) }
            return Opened(wallets: payload.wallets, dataKey: payload.dataKey)
        }
        guard let wallets = try? JSONDecoder().decode([Entry].self, from: opened) else {
            throw Error.corrupt
        }
        let dataKey = Self.newDataKey()
        try write(wallets, dataKey: dataKey, pin: pin)
        return Opened(wallets: wallets, dataKey: dataKey)
    }

    /// 32 random bytes: the private data stores' key, made once per vault.
    private static func newDataKey() -> Data {
        var bytes = Data(count: 32)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return bytes
    }

    private func write(_ wallets: [Entry], dataKey: Data, pin: String) throws {
        for wallet in wallets where !BIP39.isValid(mnemonic: wallet.mnemonic) {
            throw Error.invalidMnemonic
        }
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        guard status == errSecSuccess else { throw Error.keychain(status) }

        let kdf = KDF.current
        let sealed = try AES.GCM.seal(
            try JSONEncoder().encode(Payload(wallets: wallets, dataKey: dataKey)),
            using: Self.key(pin: pin, salt: salt, kdf: kdf)
        )
        guard let combined = sealed.combined else { throw Error.corrupt }
        try store(try JSONEncoder().encode(Vault(salt: salt, sealed: combined, format: Self.format, kdf: kdf.id, rounds: kdf.rounds)))
    }

    /// The PIN stretched into a key, at the vault's own parameters.
    ///
    /// 200,000 rounds (KDF.current) because the secret is four digits: the whole keyspace is
    /// 10,000, so the only thing standing between a stolen ciphertext and the
    /// phrase is how long each guess takes. The on-device lockout protects the
    /// screen; this protects the bytes if they ever leave the device.
    private static func key(pin: String, salt: Data, kdf: KDF) -> SymmetricKey {
        let stretched = Hashes.pbkdf2SHA512(
            password: Data(pin.utf8),
            salt: salt,
            rounds: UInt32(kdf.rounds),
            keyLength: 32
        )
        return SymmetricKey(data: stretched)
    }

    /// Replace the vault in one Keychain operation.
    ///
    /// Updated in place rather than deleted and re-added. A delete-then-add has
    /// a window with no vault at all, and an add that fails in it — no
    /// passcode any more, a full Keychain, the app killed — leaves the phrase
    /// gone with nothing to unlock. `SecItemUpdate` either
    /// swaps the data or leaves the old item untouched.
    private func store(_ payload: Data) throws {
        let changes: [String: Any] = [
            kSecValueData as String: payload,
            kSecAttrAccessible as String: Self.accessibility,
        ]
        var status = SecItemUpdate(Self.baseQuery as CFDictionary, changes as CFDictionary)
        if status == errSecItemNotFound {
            var attributes = Self.baseQuery
            attributes.merge(changes) { $1 }
            status = SecItemAdd(attributes as CFDictionary, nil)
        }
        switch status {
        case errSecSuccess:
            return
        // What a device with no passcode returns for an item that requires
        // one. Both codes have been seen for it across releases.
        case errSecNotAvailable, errSecInteractionNotAllowed:
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

    /// Put a secret behind biometrics, in the spare slot.
    ///
    /// Returns the slot it landed in. The live slot is left alone, so whatever
    /// currently opens the wallet still does until [commitBiometrics] says
    /// otherwise — which is what makes changing the unlock method survive a
    /// failure halfway through. Writing a Keychain item does not prompt; only
    /// reading one back does.
    ///
    /// `.biometryCurrentSet`, not `.userPresence`. The latter accepts the
    /// device passcode in place of a face, and keeps working for a face
    /// enrolled after the fact — so anyone who learns the passcode could enrol
    /// their own and inherit the wallet. This matches Android's
    /// `setInvalidatedByBiometricEnrollment(true)` with `BIOMETRIC_STRONG`: any
    /// change to the enrolled set destroys the item.
    @discardableResult
    public func stageBiometrics(secret: String) throws -> String {
        var error: Unmanaged<CFError>?
        let control = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            .biometryCurrentSet,
            &error
        )
        guard let control else { throw Error.noDeviceLock }

        let slot = Self.spareSlot
        SecItemDelete(Self.biometricQuery(slot: slot) as CFDictionary)
        var attributes = Self.biometricQuery(slot: slot)
        attributes[kSecValueData as String] = Data(secret.utf8)
        attributes[kSecAttrAccessControl as String] = control

        let status = SecItemAdd(attributes as CFDictionary, nil)
        switch status {
        case errSecSuccess: return slot
        case errSecInteractionNotAllowed: throw Error.noDeviceLock
        default: throw Error.keychain(status)
        }
    }

    /// Make a staged slot the live one, then destroy the slot it replaced.
    ///
    /// Call only once the vault is sealed under the staged secret. The pointer
    /// moves in one write; dying between that and the delete leaves a stale
    /// item nobody reads, which the next [stageBiometrics] overwrites.
    public func commitBiometrics(slot: String) {
        let previous = Self.liveSlot
        guard previous != slot else { return }
        Self.liveSlot = slot
        UserDefaults.standard.set(true, forKey: Self.currentSetKey)
        SecItemDelete(Self.biometricQuery(slot: previous) as CFDictionary)
    }

    /// Whether the live item was written with `.biometryCurrentSet`.
    ///
    /// Items staged before the switch still carry `.userPresence`, and the
    /// Keychain will not change an item's access control in place.
    private static let currentSetKey = "biometricCurrentSet"

    /// Re-write a `.userPresence` item under `.biometryCurrentSet`, given the
    /// secret it holds. A no-op once done.
    ///
    /// Called with a secret just read from the live item, so staging it again
    /// and committing is the same two-slot move a method change makes — the
    /// old item keeps opening the wallet until the new one is live.
    public func upgradeBiometricsIfNeeded(secret: String) {
        guard !UserDefaults.standard.bool(forKey: Self.currentSetKey),
              let slot = try? stageBiometrics(secret: secret)
        else { return }
        commitBiometrics(slot: slot)
    }

    /// Throw away a staged slot after a change that did not go through.
    public func discardBiometrics(slot: String) {
        guard slot != Self.liveSlot else { return }
        SecItemDelete(Self.biometricQuery(slot: slot) as CFDictionary)
    }

    public func forgetBiometrics() {
        for slot in Self.slots {
            SecItemDelete(Self.biometricQuery(slot: slot) as CFDictionary)
        }
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
        // Only asked for when the configured method uses biometrics, so the
        // item should be there. A `.biometryCurrentSet` item that vanished is
        // what an enrolment change looks like from here — the Keychain does
        // not say so in as many words.
        case errSecItemNotFound: throw Error.biometricsInvalidated
        case errSecUserCanceled, errSecAuthFailed:
            // Removing every face also invalidates the item, but surfaces as a
            // failed authentication — there is nothing left to match.
            var reason: NSError?
            if !LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &reason),
               reason?.code == LAError.biometryNotEnrolled.rawValue {
                throw Error.biometricsInvalidated
            }
            throw Error.authenticationFailed
        default: throw Error.keychain(status)
        }
    }

    /// Fold a PIN together with the half held behind biometrics.
    ///
    /// Hashed rather than concatenated so the PIN's boundary is not visible in
    /// the result, and so the two always produce a fixed-width secret whatever
    /// their lengths.
    public static func combine(pin: String, half: String) -> String {
        let digest = SHA256.hash(data: Data("\(pin)|\(half)".utf8))
        return Data(digest).base64EncodedString()
    }

    /// A secret for a wallet with no PIN, or the half a two-factor wallet
    /// keeps behind the prompt. 32 random bytes, so what stands behind the
    /// biometric prompt is a real key rather than four digits.
    public static func generatedSecret() -> String {
        var bytes = Data(count: 32)
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!)
        }
        // A secret from a failed generator (all zeros) would guard nothing.
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return bytes.base64EncodedString()
    }

    /// The two slots a biometric secret can live in.
    ///
    /// Slot `a` deliberately keeps the original account name, so a wallet
    /// enrolled before slots existed is already the live one and needs no
    /// migration.
    private static let slots = ["a", "b"]

    private static func biometricAccount(slot: String) -> String {
        slot == "a" ? biometricAccount : "\(biometricAccount).\(slot)"
    }

    /// Which slot currently holds the live secret.
    ///
    /// Not a secret itself — it names a slot, nothing more — so UserDefaults is
    /// the right place for it. It is lost when the app is deleted, and so is
    /// the vault: `AppModel.clearIfReinstalled` wipes both together.
    private static var liveSlot: String {
        get { UserDefaults.standard.string(forKey: "biometricSlot") ?? slots[0] }
        set { UserDefaults.standard.set(newValue, forKey: "biometricSlot") }
    }

    private static var spareSlot: String {
        slots.first { $0 != liveSlot } ?? slots[1]
    }

    private static func biometricQuery(slot: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: biometricAccount(slot: slot),
        ]
    }

    private static var biometricQuery: [String: Any] {
        biometricQuery(slot: liveSlot)
    }

    /// Re-seal the vault under a new secret, keeping the wallets.
    public func reseal(from old: String, to new: String) throws {
        let opened = try open(pin: old)
        try write(opened.wallets, dataKey: opened.dataKey, pin: new)
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
        forgetBiometrics()
    }

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
