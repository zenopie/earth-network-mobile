import BigInt
import EarthCore
import Observation
import SwiftUI

/// What the whole app reads from.
///
/// One model rather than one per screen: the tabs overlap heavily — balances
/// appear on three of them, the registration state gates two — and four view
/// models querying the same LCD on every tab switch is both slower and capable
/// of disagreeing with itself on screen.
@Observable
@MainActor
public final class AppModel {

    public enum Phase: Equatable {
        /// Deciding whether there is a wallet at all.
        case launching
        /// The wallet cannot be stored here, and why: the device has no
        /// passcode, so the Keychain refuses the phrase. Shown instead of
        /// waiting on a vault that will never open.
        case unavailable(String)
        /// There is no wallet yet.
        case setup
        /// There is one, and it has not been unlocked this session.
        case locked
        case ready
    }

    public private(set) var phase: Phase = .launching
    public private(set) var address: String = ""

    /// Every wallet held, and which one is on screen.
    ///
    /// Loaded lazily: reading them needs the phrase, and the phrase needs a
    /// biometric prompt, so this stays empty until the wallets screen asks.
    public private(set) var wallets: [WalletStore.Entry] = []
    public private(set) var selected = 0

    /// The name the current wallet was created under. What the top bar says.
    public private(set) var walletName: String = "Wallet 1"

    /// Balances by denom, in base units.
    public private(set) var balances: [String: BigInt] = [:]
    /// This wallet's private side: notes, registration, private actions.
    /// Built from the selected wallet's mnemonic when it is unlocked or
    /// switched to, and dropped on lock. Nil while locked.
    public private(set) var privacy: PrivacyWallet?
    /// Shielded balances by denom (uerth, uanml, derth/<valoper>, ...), spendable notes only.
    public private(set) var shielded: [String: UInt64] = [:]
    public private(set) var shieldedAddress: String = ""
    public private(set) var identityStatus: WalletSync.IdentityStatus = .none
    /// When ANML can next be claimed: 0 for now, nil without a live registration.
    public private(set) var claimOpensAt: Int64?
    /// Why the last privacy sync failed, if it did.
    public private(set) var privacySyncError: String?
    /// Spendable note counts per denom with more than one note.
    public private(set) var mergeable: [String: Int] = [:]
    /// The most shielded ERTH one unshield can spend, fee included: its
    /// largest max_actions_per_bundle notes. Under `shieldedErth` only when
    /// the notes are spread over more than that.
    public private(set) var unshieldableErth: UInt64 = 0
    /// This wallet's Groundworks positions (public positions whose key is ours).
    public private(set) var positions: [OwnedPosition] = []
    /// This wallet's private stake per validator: what may move now, what waits for its window.
    public private(set) var stakeHoldings: [PrivacyWallet.StakeHolding] = []
    /// The chain's label window as last read (0: never): how long moved stake stays put.
    public private(set) var labelWindowSeconds: UInt64 = 0
    /// x/assembly's open removal ballots.
    public private(set) var removalBallots: [PrivacyReads.RemovalBallot] = []
    /// Live rate_v (ERTH per derth) of every validator with a book, from the
    /// validator list (read whole, so no read names which ones this wallet
    /// holds). Missing until the first read lands.
    public private(set) var derthRates: [String: Decimal] = [:]
    /// R: how long a caretaker split counts after it is cast (default 365 days).
    public private(set) var leaseSeconds: Int64 = 365 * 86_400
    /// handle_lease_seconds: a handle's lease from each renewal.
    public private(set) var handleLeaseSeconds: Int64 = Handles.defaultLeaseSeconds
    /// This identity's handle ("" for none), its directory entry, and whether it moved one away.
    public private(set) var handle: String = ""
    public private(set) var handleEntry: HandleEntry?
    public private(set) var handleMovedOut = false
    /// Why the handle directory could not be read, if it could not.
    public private(set) var handleDirectoryError: String?
    /// The caretaker split's expiry (0: none) and whether it moved away.
    public private(set) var caretakerExpiresAt: Int64 = 0
    public private(set) var caretakerMovedOut = false
    /// This identity replaced another at this time (0: a fresh passport).
    public private(set) var predecessorAt: UInt64 = 0
    /// What is due (a claim, a renewal): reminders, never actions taken unasked.
    public private(set) var reminders: [Reminders.Reminder] = []
    /// Non-free directory entries naming this wallet's address.
    public private(set) var addressedHandles: [HandleEntry] = []
    /// The split is held but was restored without its options.
    public private(set) var caretakerSplitUnknown = false
    /// Moves away from this identity not yet confirmed or not yet recorded in the new wallet, and moves to it
    /// the chain has not confirmed; the store id of the wallet the moves went to ("" none yet).
    public private(set) var outgoingMoves: [PendingMove] = []
    public private(set) var incomingMoves: [PendingMove] = []
    public private(set) var switchTarget = ""
    /// Undelegations waiting for their payout, from this wallet's own record (nothing asked of the chain).
    public private(set) var pendingUnbonds: [PendingUnbond] = []

    public struct OwnedPosition: Identifiable, Sendable {
        public let position: PrivacyReads.Position
        /// Its owner-tag counter (PrivacyKeys.otagSalt): what proves it ours.
        public let counter: UInt32
        public var id: UInt64 { position.id }
    }
    /// Recent transactions, nil until the first load lands.
    ///
    /// The distinction matters: a zero that is really "not loaded yet" is the
    /// one wrong answer a wallet must never give, so the list shows
    /// placeholders while this is nil rather than "nothing yet".
    private(set) var activity: [ActivityRow]?

    public private(set) var pools: [Dex.Pool] = []
    public private(set) var swapFeePercent = Decimal(string: "0.3")!
    /// The escrow a withdrawal from a pool waits out, in seconds.
    ///
    /// A chain parameter, so it is read rather than assumed — the withdraw
    /// sheet states the wait before anyone commits to it, and a hardcoded
    /// number would quietly become a lie the day the parameter changed.
    public private(set) var lpUnbondingSeconds: Int64 = 0
    /// Withdrawals from pools waiting out their escrow.
    ///
    /// Between submitting one and it landing there is nothing in the balance to
    /// show for it — the shares have left and the assets have not arrived — so
    /// without this the wait looks like the funds went nowhere.
    public private(set) var lpUnbondings: [Dex.Unbonding] = []
    /// Bonded validators, from the validator list (Query/Validators).
    public private(set) var validators: [Staking.Validator] = []
    /// Bonded validators taking stake now: what the stake and move pickers
    /// offer (a jailed or tombstoned one, or a book settling, the chain refuses).
    public private(set) var stakeTargets: [String] = []
    /// Every validator's moniker and commission, whatever its status (the list's).
    public private(set) var validatorNames: [String: (moniker: String, commission: Double)] = [:]
    public private(set) var delegations: [Staking.Delegation] = []
    public private(set) var unbondings: [Staking.UnbondingEntry] = []
    public private(set) var rewards: BigInt = 0
    public private(set) var totalBonded: BigInt = 0

    /// Whether figures are shown or masked.
    ///
    /// Shoulder-surfing is the reason it exists, and the state is deliberately
    /// not persisted: unhiding is a decision about the room you are in, not a
    /// setting.
    /// Which tab is showing.
    ///
    /// On the model rather than in the tab view because screens need to send
    /// you elsewhere — the home screen's Earn card is a link to a tab, and a
    /// card that cannot reach the selection is a button that does nothing.
    public var tab: Tab = .wallet

    public private(set) var balancesVisible = true

    public private(set) var refreshing = false
    /// The last query failure, if the most recent refresh did not complete.
    ///
    /// Shown as a banner rather than an alert: a wallet that cannot reach the
    /// chain still has an address to show and a phrase to back up, and a modal
    /// would block both.
    public private(set) var lastError: String?

    /// The unlock secret, held only while the app is unlocked.
    ///
    /// Signing decrypts the vault again with it rather than using `wallets`.
    /// Unlike Android, `wallets` does keep every phrase resident for the
    /// session (the wallet list, a switch's target keys and the private side
    /// read them); either way the secret alone opens the vault, so what bounds
    /// the exposure is how soon the session ends: locking drops both, on
    /// device lock and before the app is suspended in the background
    /// (`scenePhaseChanged`).
    private var sessionPin: String?

    /// Guards against a second unlock starting while one is in flight.
    ///
    /// Both entry points raise a system prompt, and both are reachable more
    /// than once — a re-run `task`, a second tap, a re-created view. Without
    /// this the user gets two prompts stacked, and answering the first leaves
    /// the second on screen.
    private var unlocking = false

    /// When the scene last went to the background, if it is there now.
    ///
    /// A continuous clock rather than `Date`: wall time can be wound back in
    /// Settings, and this one keeps counting while the device sleeps.
    private var backgroundedAt: ContinuousClock.Instant?

    /// How long the app may sit in the background before it locks.
    ///
    /// Measured from `.background`, not `.inactive`. The Face ID prompt and the
    /// NFC reader sheet both take the scene only as far as `.inactive`, so
    /// neither starts this clock — and a minute is generous for anything that
    /// does, such as a trip to the Mail app for a code.
    private static let backgroundGrace: Duration = .seconds(60)

    /// Set when the biometric secret turned out to be gone because the
    /// enrolled faces or fingers changed.
    ///
    /// The unlock screen needs to tell this apart from a wrong PIN or a
    /// cancelled prompt: it is permanent, and the only way forward is the
    /// recovery phrase.
    public private(set) var biometricsInvalidated = false

    /// How this wallet is opened. Not a secret, so it lives in defaults —
    /// knowing that a wallet unlocks biometrically does not help anyone open it.
    public private(set) var method: WalletStore.Method =
        WalletStore.Method(rawValue: UserDefaults.standard.string(forKey: "unlockMethod") ?? "")
            ?? .pin

    public let client: EarthClient
    public let store: WalletStore

    public init(client: EarthClient = EarthClient(), store: WalletStore = WalletStore()) {
        self.client = client
        self.store = store
    }

    // MARK: - session

    public func start() {
        watchDeviceLock()
        clearIfReinstalled()
        walletName = UserDefaults.standard.string(forKey: "walletName") ?? walletName
        #if targetEnvironment(simulator)
        // `-demoWallet <phrase>` opens the app straight onto the tabs with a
        // known wallet. A simulator has no way to be driven from the command
        // line — no taps, no text — so without this the only screen reachable
        // outside Xcode is the first one, which makes the four tabs impossible
        // to look at while working on them.
        //
        // Simulator-only and compiled out of every device build.
        if let phrase = UserDefaults.standard.string(forKey: "demoWallet"),
           BIP39.isValid(mnemonic: phrase) {
            // A fixed PIN, since nothing can type one either. A simulator
            // with no passcode set refuses the vault (the Keychain item needs
            // one), so the failure is shown rather than leaving the launch
            // spinner up forever.
            Task {
                do {
                    try await adopt(mnemonic: phrase, name: "Wallet 1", method: .pin, pin: "0000")
                } catch {
                    phase = .unavailable(unavailableReason(error))
                }
            }
            return
        }
        #endif
        phase = store.exists ? .locked : .setup
    }

    /// How an unlock attempt ended, so the lock screen can say what actually
    /// happened: a Keychain error or an unreadable vault is not a wrong PIN,
    /// and telling a user so sends them hunting for a PIN they never forgot.
    public enum UnlockOutcome: Equatable {
        case unlocked
        case wrongPin
        /// The prompt was dismissed. Not a guess, so nothing is counted.
        case cancelled
        /// Another attempt is in flight, or the backoff is running.
        case busy
        /// The biometric secret is gone; `biometricsInvalidated` says why.
        case invalidated
        case failed(String)
    }

    /// Unlock with the PIN.
    ///
    /// A wrong PIN is counted, because four digits is 10,000 combinations and
    /// the only thing making that a secret is how many guesses are allowed.
    public func unlock(pin: String) async -> UnlockOutcome {
        guard !UnlockAttempts.status().lockedOut, !unlocking else { return .busy }
        unlocking = true
        defer { unlocking = false }

        do {
            let store = self.store
            let secret = try await unlockSecret(pin: pin, reason: "Unlock your Earth wallet")

            // Stretching is deliberately slow — 200,000 rounds — so it runs
            // off the main actor. On the main thread it is a visible freeze on
            // every unlock and, because signing re-opens the vault, on every
            // transaction too.
            let opened = try await Task.detached { try store.open(pin: secret) }.value
            let wallets = opened.wallets
            guard !wallets.isEmpty else { throw WalletStore.Error.notFound }
            UnlockAttempts.recordSuccess()
            sessionPin = secret
            PrivacySession.setDataKey(opened.dataKey)
            self.wallets = wallets
            selected = min(UserDefaults.standard.integer(forKey: "selectedWallet"),
                           wallets.count - 1)
            address = wallets[selected].address
            walletName = wallets[selected].name
            lastError = nil
            openPrivacy()
            phase = .ready
            await refresh()
            return .unlocked
        } catch {
            switch error {
            case WalletStore.Error.wrongPin:
                UnlockAttempts.recordFailure()
                return .wrongPin
            case WalletStore.Error.authenticationFailed:
                // A dismissed prompt is not a wrong PIN, and counting it as
                // one would lock someone out for tapping cancel.
                return .cancelled
            case WalletStore.Error.biometricsInvalidated:
                biometricsInvalidated = true
                return .invalidated
            default:
                return .failed(describe(error))
            }
        }
    }

    /// The secret the vault is sealed under, assembled however this wallet's
    /// method says. Raises the biometric prompt when the method has one.
    ///
    /// A two-factor wallet needs the half held behind the prompt as well —
    /// the PIN on its own decrypts nothing.
    private func unlockSecret(pin: String?, reason: String) async throws -> String {
        guard method.usesBiometrics else { return pin ?? "" }
        let store = self.store
        // Off the main actor. `SecItemCopyMatching` blocks its thread until
        // the user answers, and blocking the main thread is what the system
        // needs free to present the prompt at all.
        let half = try await Task.detached { try store.biometricSecret(reason: reason) }.value
        store.upgradeBiometricsIfNeeded(secret: half)
        return method == .both ? WalletStore.combine(pin: pin ?? "", half: half) : half
    }

    /// Proof that the user just authenticated, for the actions that should
    /// not ride on an unlock that happened minutes ago.
    ///
    /// The secret is not public: the only way to get one of these is
    /// `reauthenticate`, so `setMethod` cannot be called on the strength of
    /// the session alone.
    public struct Confirmation {
        public let wallets: [WalletStore.Entry]
        fileprivate let secret: String
    }

    /// Ask for the wallet's unlock again — PIN, prompt or both — and prove it
    /// by opening the vault.
    ///
    /// For revealing a phrase and changing the unlock method. An unlocked
    /// phone left on a table should not hand over either: one is the wallet
    /// itself, the other lets whoever holds the phone replace the PIN.
    /// Wrong PINs count toward the same lockout the unlock screen uses.
    public func reauthenticate(pin: String?, reason: String) async throws -> Confirmation {
        guard phase == .ready, sessionPin != nil else { throw WalletStore.Error.notFound }
        guard !UnlockAttempts.status().lockedOut else { throw WalletStore.Error.authenticationFailed }
        do {
            let store = self.store
            let secret = try await unlockSecret(pin: pin, reason: reason)
            let wallets = try await Task.detached { try store.unlock(pin: secret) }.value
            UnlockAttempts.recordSuccess()
            return Confirmation(wallets: wallets, secret: secret)
        } catch WalletStore.Error.wrongPin {
            UnlockAttempts.recordFailure()
            throw WalletStore.Error.wrongPin
        } catch WalletStore.Error.biometricsInvalidated {
            biometricsInvalidated = true
            throw WalletStore.Error.biometricsInvalidated
        }
    }

    /// Build the secret a new wallet will be sealed under, storing whatever
    /// half belongs behind the prompt.
    /// Build the secret a method implies, staging any biometric half.
    ///
    /// The slot comes back with it and is *not* live yet. Whoever calls this
    /// owns the outcome: commit the slot once the vault is actually sealed
    /// under the secret, discard it if that fails. Until one of those happens
    /// the wallet still opens the way it did before.
    private func seal(
        method: WalletStore.Method,
        pin: String?
    ) throws -> (secret: String, slot: String?) {
        switch method {
        case .pin:
            return (pin ?? "", nil)
        case .biometrics:
            // Nothing to remember: the whole key lives behind the prompt.
            let secret = WalletStore.generatedSecret()
            return (secret, try store.stageBiometrics(secret: secret))
        case .both:
            // Only a *half* goes behind the prompt. The other half is the PIN,
            // and the vault opens for neither on its own.
            let half = WalletStore.generatedSecret()
            let slot = try store.stageBiometrics(secret: half)
            return (WalletStore.combine(pin: pin ?? "", half: half), slot)
        }
    }

    /// Unlock biometrically — Face ID, Touch ID, or whatever this device has.
    ///
    /// Only for a wallet sealed under the prompt alone. A two-factor wallet
    /// goes through the PIN path, which asks for both.
    public func unlockWithBiometrics() async -> UnlockOutcome {
        guard method == .biometrics, !unlocking else { return .busy }
        unlocking = true
        defer { unlocking = false }

        do {
            let store = self.store
            let secret = try await unlockSecret(pin: nil, reason: "Unlock your Earth wallet")
            let opened = try await Task.detached { try store.open(pin: secret) }.value
            let wallets = opened.wallets
            guard !wallets.isEmpty else { throw WalletStore.Error.notFound }
            UnlockAttempts.recordSuccess()
            sessionPin = secret
            PrivacySession.setDataKey(opened.dataKey)
            self.wallets = wallets
            selected = min(UserDefaults.standard.integer(forKey: "selectedWallet"),
                           wallets.count - 1)
            address = wallets[selected].address
            walletName = wallets[selected].name
            lastError = nil
            openPrivacy()
            phase = .ready
            await refresh()
            return .unlocked
        } catch {
            // A cancelled prompt is not a failure worth reporting — the PIN
            // pad is still on screen behind it, and on a biometrics-only
            // wallet the button is still there to try again.
            if case WalletStore.Error.authenticationFailed = error { return .cancelled }
            if case WalletStore.Error.biometricsInvalidated = error {
                biometricsInvalidated = true
                return .invalidated
            }
            return .failed(describe(error))
        }
    }

    /// Change how this wallet is opened.
    ///
    /// The vault is re-sealed rather than re-gated: switching to a PIN means
    /// the PIN becomes the key, and switching away from one means it stops
    /// being able to open anything.
    ///
    /// Takes a fresh `Confirmation` rather than the session's secret, so the
    /// old method is asked for again before it can be replaced.
    public func setMethod(_ new: WalletStore.Method, pin: String?, confirmedBy confirmation: Confirmation) throws {
        guard sessionPin != nil else { throw WalletStore.Error.notFound }
        let current = confirmation.secret
        let sealed = try seal(method: new, pin: pin)

        // Re-seal before anything is promoted or thrown away. If this throws,
        // the old secret is still live and still opens the wallet — the staged
        // slot was never more than a spare.
        do {
            try store.reseal(from: current, to: sealed.secret)
        } catch {
            if let slot = sealed.slot { store.discardBiometrics(slot: slot) }
            throw error
        }

        if let slot = sealed.slot {
            store.commitBiometrics(slot: slot)
        } else if !new.usesBiometrics {
            store.forgetBiometrics()
        }
        sessionPin = sealed.secret
        method = new
        UserDefaults.standard.set(new.rawValue, forKey: "unlockMethod")
    }

    /// The PIN, for the paths that need to read the vault again.
    ///
    /// Returns nil when locked, which is the only correct answer then.
    public var pin: String? { sessionPin }

    /// Create the wallet under whichever secret the chosen method implies.
    public func adopt(
        mnemonic: String,
        name: String,
        method: WalletStore.Method,
        pin: String?
    ) async throws {
        // Again here, so no caller can store a phrase that validates but
        // derives a different key than the one it names.
        let mnemonic = BIP39.canonical(mnemonic)
        let sealed = try seal(method: method, pin: pin)
        let secret = sealed.secret
        do {
            try store.create(mnemonic: mnemonic, name: name, pin: secret)
        } catch {
            if let slot = sealed.slot { store.discardBiometrics(slot: slot) }
            throw error
        }
        if let slot = sealed.slot { store.commitBiometrics(slot: slot) }
        self.method = method
        UserDefaults.standard.set(method.rawValue, forKey: "unlockMethod")
        sessionPin = secret
        UnlockAttempts.recordSuccess()
        let opened = try store.open(pin: secret)
        PrivacySession.setDataKey(opened.dataKey)
        wallets = opened.wallets
        walletName = name
        selected = 0
        UserDefaults.standard.set(name, forKey: "walletName")
        UserDefaults.standard.set(0, forKey: "selectedWallet")
        address = try EarthKey(mnemonic: mnemonic).address
        openPrivacy()
        phase = .ready
        await refresh()
    }

    public func forget() {
        closePrivacy()
        // The wallets' private data goes with them; a failure is shown, not dropped.
        do { try PrivacySession.forgetAll() } catch { privacySyncError = describe(error) }
        store.delete()
        sessionPin = nil
        PrivacySession.setDataKey(nil)
        wallets = []
        biometricsInvalidated = false
        address = ""
        balances = [:]
        activity = nil
        phase = .setup
    }

    /// Treat deleting the app as deleting the wallet.
    ///
    /// iOS keeps Keychain items when an app is removed, so without this a
    /// reinstall silently restores the previous wallet — someone who deleted
    /// the app to be rid of it would still have their phrase on the device,
    /// with no way to know. The container, and so `UserDefaults`, *is* removed,
    /// which is what makes a reinstall detectable at all.
    ///
    /// The cost is the other half of the trade: an accidental delete now
    /// destroys a wallet whose phrase was never written down. That is the
    /// same bargain every self-custody wallet makes, and the phrase is the
    /// backup it asks you to keep for exactly this.
    private func clearIfReinstalled() {
        let marker = "installed"
        guard !UserDefaults.standard.bool(forKey: marker) else { return }
        store.delete()
        // The attempt count lives in the Keychain too, and has nothing left
        // to protect once the vault is gone.
        UnlockAttempts.recordSuccess()
        UserDefaults.standard.removeObject(forKey: "walletName")
        UserDefaults.standard.removeObject(forKey: "selectedWallet")
        UserDefaults.standard.set(true, forKey: marker)
    }

    /// Re-read the wallet list from the vault.
    public func loadWallets() {
        guard let sessionPin else { return }
        wallets = (try? store.unlock(pin: sessionPin)) ?? wallets
        selected = min(UserDefaults.standard.integer(forKey: "selectedWallet"),
                       max(0, wallets.count - 1))
    }

    /// Switch to another wallet.
    ///
    /// Everything on screen belongs to the old one, so the whole app reloads
    /// rather than the address alone. Anything less leaves a stale balance
    /// behind a fresh address.
    public func select(_ index: Int) async {
        guard wallets.indices.contains(index) else { return }
        selected = index
        walletName = wallets[index].name
        address = wallets[index].address
        UserDefaults.standard.set(index, forKey: "selectedWallet")
        UserDefaults.standard.set(walletName, forKey: "walletName")

        balances = [:]
        activity = nil
        closePrivacy()
        openPrivacy()
        delegations = []
        unbondings = []
        rewards = 0
        await refresh()
    }

    /// Add a wallet and switch to it.
    public func addWallet(mnemonic: String, name: String) async throws {
        guard let sessionPin else { throw WalletStore.Error.notFound }
        let index = try store.add(mnemonic: BIP39.canonical(mnemonic), name: name, pin: sessionPin)
        loadWallets()
        await select(index)
    }

    public func toggleBalances() {
        balancesVisible.toggle()
    }

    public func lock() {
        closePrivacy()
        sessionPin = nil
        PrivacySession.setDataKey(nil)
        wallets = []
        lastError = nil
        phase = store.exists ? .locked : .setup
    }

    /// Follow the scene, locking after `backgroundGrace` away.
    ///
    /// Checked on the way back to `.active` rather than timed in the
    /// background, where a suspended app runs nothing. The privacy cover is
    /// already up by then, so nothing is shown between returning and locking.
    public func scenePhaseChanged(to scenePhase: ScenePhase) {
        switch scenePhase {
        case .background:
            backgroundedAt = backgroundedAt ?? .now
            holdBackgroundLock()
        case .active:
            defer { backgroundedAt = nil }
            endBackgroundLock()
            guard phase == .ready, let away = backgroundedAt else { return }
            if ContinuousClock.now - away > Self.backgroundGrace { lock() }
        default:
            break
        }
    }

    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var backgroundLock: Task<Void, Never>?
    private var deviceLockObserver: NSObjectProtocol?

    /// A suspended app runs nothing, so a check on return alone would keep
    /// the secret and every phrase in memory for as long as it sits
    /// suspended. The background time the system grants is used to lock at
    /// the grace's end, or when that time runs out, whichever comes first,
    /// so the session never outlives the app being in the foreground by
    /// more than the grace.
    private func holdBackgroundLock() {
        guard phase == .ready, backgroundTask == .invalid else { return }
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "lock") { [weak self] in
            MainActor.assumeIsolated {
                self?.lock()
                self?.endBackgroundLock()
            }
        }
        backgroundLock = Task { [weak self] in
            try? await Task.sleep(for: Self.backgroundGrace)
            guard !Task.isCancelled, let self else { return }
            self.lock()
            self.endBackgroundLock()
        }
    }

    private func endBackgroundLock() {
        backgroundLock?.cancel()
        backgroundLock = nil
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    /// Locks the moment the device does, as Android locks on screen-off.
    private func watchDeviceLock() {
        guard deviceLockObserver == nil else { return }
        deviceLockObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.phase == .ready else { return }
                self.lock()
            }
        }
    }

    // MARK: - chain

    /// Everything the tabs need, in one pass.
    ///
    /// Concurrently, because the LCD serves each of these from a different
    /// module and serially this is the difference between a tab that appears
    /// and a tab that populates.
    public func refresh() async {
        guard !address.isEmpty else { return }
        refreshing = true
        defer { refreshing = false }

        // One probe that is allowed to throw.
        //
        // Every query below swallows its failure and returns empty, which is
        // right for them — a fresh account has no balances and a young chain
        // has no pools, and neither is an error. But it leaves nothing able to
        // tell "empty" from "unreachable", so the banner would never appear at
        // the one moment it is needed. This asks the chain a question it always
        // has an answer to.
        async let reachable: Void = probe()

        async let balances = client.balances(address)
        async let privacySync: Void = syncPrivacy()
        async let pools = client.pools()
        async let fee = client.swapFeePercent()
        async let lpUnbonding = client.lpUnbondingSeconds()
        async let lpQueue = client.unbondings(address)
        async let validatorList = PrivacyQueries(rest: client.rest).validators()
        async let delegations = client.delegations(address)
        async let unbondings = client.unbondingDelegations(address)
        async let rewards = client.totalRewards(address)
        async let bonded = client.totalBonded()
        async let transactions = client.transactions(for: address)

        self.balances = await balances.compactMapValues { BigInt($0) }
        self.pools = await pools
        self.swapFeePercent = Decimal(string: await fee) ?? self.swapFeePercent
        self.lpUnbondingSeconds = await lpUnbonding
        self.lpUnbondings = await lpQueue
        applyValidatorList((try? await validatorList) ?? PrivacyQueries.cachedValidators)
        self.delegations = await delegations
        self.unbondings = await unbondings
        self.rewards = BigInt(await rewards) ?? 0
        self.totalBonded = BigInt(await bonded) ?? 0
        let signer = address
        self.activity = await transactions.compactMap { ActivityRow(tx: $0, self: signer) }
        await reachable
        await privacySync
    }

    private func probe() async {
        do {
            _ = try await client.rest.get("/cosmos/base/tendermint/v1beta1/syncing")
            lastError = nil
        } catch {
            lastError = "Cannot reach \(client.rest.lcd.host ?? "the chain"). Showing the last known state."
        }
    }

    // MARK: - derived

    public func balance(_ token: Token) -> BigInt { balances[token.denom] ?? 0 }

    /// LP shares held in one pool, public and private.
    ///
    /// Public shares are an ordinary bank denom — `dexlp/<id>` — so they
    /// arrive with every other balance; private ones (a shielded deposit's)
    /// are share notes of the same denom in the pool. Both are this wallet's.
    public func lpShares(poolID: UInt64) -> BigInt {
        publicLPShares(poolID: poolID) + privateLPShares(poolID: poolID)
    }

    public func publicLPShares(poolID: UInt64) -> BigInt { balances[Dex.shareDenom(poolID: poolID)] ?? 0 }

    /// Share notes (dexlp/<id>) in the pool: withdrawn privately.
    public func privateLPShares(poolID: UInt64) -> BigInt { BigInt(shielded[Dex.shareDenom(poolID: poolID)] ?? 0) }

    /// Tokens worth listing: the registry, plus anything held that it does not
    /// know about, minus registry entries with no balance beyond the two this
    /// chain is about.
    public var holdings: [(token: Token, amount: BigInt)] {
        var rows: [(Token, BigInt)] = []
        for token in Token.all {
            let amount = balance(token)
            // ERTH and ANML always show: one is gas, the other is the point of
            // registering, and a zero of either is information.
            if amount > 0 || token == .erth || token == .anml {
                rows.append((token, amount))
            }
        }
        for (denom, amount) in balances where Token.named(denom) == nil && amount > 0 {
            rows.append((Token.unknown(denom: denom), amount))
        }
        return rows.sorted { lhs, rhs in
            if (lhs.1 > 0) != (rhs.1 > 0) { return lhs.1 > 0 }
            return lhs.0.symbol < rhs.0.symbol
        }
    }

    /// A live registration in the identity tree (private: nothing on chain
    /// names this wallet's registration).
    public var isRegistered: Bool { identityStatus == .live }

    public var canClaimAnml: Bool { claimOpensAt == 0 }

    /// Shielded ERTH: what every private action's fee is paid from.
    public var shieldedErth: UInt64 { shielded["uerth"] ?? 0 }

    /// Private stake (derth notes) per validator.
    public var privateStake: [String: UInt64] {
        shielded.filter { $0.key.hasPrefix("derth/") }
    }

    /// Private stake in derth, positions included. A count of notes' units,
    /// not ERTH: what decides whether the wallet can stake-vote at all.
    public var privateStakeTotal: UInt64 {
        // Saturating: amounts a node publishes never trap a sum.
        PrivateMsgs.saturatingAdd(privateStake.values.reduce(0, PrivateMsgs.saturatingAdd),
                                  positions.reduce(0) { PrivateMsgs.saturatingAdd($0, $1.position.derth) })
    }

    /// What `derth` derth/`validator` is worth in uerth at the live rate:
    /// floor(derth x rate_v), as the chain converts it. At face value until
    /// the rate has been read (it starts at 1 and only rewards move it).
    public func derthValue(_ derth: UInt64, validator: String) -> UInt64 {
        PrivacyWallet.derthValue(derth, rate: derthRates[validator] ?? 1)
    }

    /// Private stake in ERTH (uerth): every derth note and position at its
    /// validator's live rate.
    public var privateStakeValue: UInt64 {
        let notes = privateStake.reduce(UInt64(0)) {
            PrivateMsgs.saturatingAdd($0, derthValue($1.value, validator: String($1.key.dropFirst("derth/".count))))
        }
        return PrivateMsgs.saturatingAdd(notes, positions.reduce(0) {
            PrivateMsgs.saturatingAdd($0, derthValue($1.position.derth, validator: $1.position.validator))
        })
    }

    // MARK: - privacy

    /// Builds the selected wallet's private side from its mnemonic. Nothing
    /// here sends anything: every tx the app makes is one the user confirmed
    /// on a sheet. Undelegations pay out by themselves (the chain mints the
    /// payout); the ANML claim, the caretaker vote and the handle are
    /// reminders (`reminders`).

    private func openPrivacy() {
        guard privacy == nil, wallets.indices.contains(selected) || !wallets.isEmpty else { return }
        let entry = wallets.indices.contains(selected) ? wallets[selected] : wallets[0]
        do {
            let w = try PrivacySession.open(mnemonic: entry.mnemonic, client: client)
            privacy = w
            shieldedAddress = w.address.encode()
            publishPrivacy()
        } catch {
            privacySyncError = describe(error)
        }
    }

    private func closePrivacy() {
        privacy = nil
        pendingUnbonds = []
        shielded = [:]
        shieldedAddress = ""
        identityStatus = .none
        claimOpensAt = nil
        mergeable = [:]
        unshieldableErth = 0
        positions = []
        stakeHoldings = []
        labelWindowSeconds = 0
        derthRates = [:]
        handle = ""; handleEntry = nil; handleMovedOut = false; handleDirectoryError = nil
        caretakerExpiresAt = 0; caretakerMovedOut = false; predecessorAt = 0; reminders = []
        addressedHandles = []; caretakerSplitUnknown = false; outgoingMoves = []; incomingMoves = []; switchTarget = ""
        PrivacyProving.registrationMayFollow = true
    }

    /// Copies the wallet's snapshot into what the screens read.
    func publishPrivacy() {
        guard let w = privacy else { return }
        let snap = w.snapshot
        shielded = snap.balances
        pendingUnbonds = snap.pendingUnbonds
        identityStatus = snap.identityStatus
        claimOpensAt = w.claimOpensAt()
        mergeable = snap.mergeable.merging(snap.stakeMergeable) { a, _ in a }
        unshieldableErth = snap.unshieldableErth
        handle = snap.handle; handleMovedOut = snap.handleMovedOut
        caretakerMovedOut = snap.caretakerMovedOut; caretakerSplitUnknown = snap.caretakerSplitUnknown
        outgoingMoves = snap.pendingMoves.filter { !$0.incoming }; incomingMoves = snap.pendingMoves.filter(\.incoming)
        switchTarget = snap.switchTarget
        predecessorAt = snap.identity?.predecessorAt ?? 0
        // A registered wallet still may register in this launch if it can
        // switch to another wallet, which may not be.
        PrivacyProving.registrationMayFollow = snap.identityStatus != .live || wallets.count > 1
    }

    /// A full sync of the indexer's streams (nothing asked about this
    /// wallet), then the public reads the private screens show.
    func syncPrivacy() async {
        guard let w = privacy else { return }
        // A sync error, else roots the chain has not vouched for (no private
        // tx is built on them), else a registration whose leaf did not match:
        // each is shown, none is hidden.
        do {
            try await w.sync()
            let snap = w.snapshot
            privacySyncError = (snap.rootsVerified ? nil : snap.rootsError) ?? snap.saveError ?? snap.pendingRegistration?.failure
        } catch {
            privacySyncError = describe(error)
        }
        publishPrivacy()
        let queries = PrivacyQueries(rest: client.rest)
        if let mine = try? await w.positions() {
            positions = mine.map { OwnedPosition(position: $0.position, counter: $0.counter) }
        }
        await refreshStakeHoldings()
        await refreshRemovalBallots()
        // The list the sync just read.
        applyValidatorList(PrivacyQueries.cachedValidators)
        if let p = try? await queries.personhoodParams() { leaseSeconds = p.caretakerVoteSeconds }
        // The claim wait uses the lease the chain's bound uses (LeaseBounds: the longest ever in force), never Params.
        if let b = try? await queries.leaseBounds(), (1 ... Handles.maxAheadSeconds).contains(b.handleLeaseSeconds) { handleLeaseSeconds = b.handleLeaseSeconds }
        await refreshPersonal()
    }

    /// This wallet's stake per validator (the chain's debt view is read only when a label is held).
    func refreshStakeHoldings() async {
        guard let w = privacy else { return }
        stakeHoldings = await w.stakeHoldings()
        labelWindowSeconds = w.labelWindowSeconds
    }

    /// Re-reads this identity's handle (from the whole directory, never a
    /// query for it alone) and caretaker standing, and the reminders due.
    func refreshPersonal() async {
        guard let w = privacy else { return }
        // Every wallet reads the chain's own directory, whole, holder or not,
        // and squares its handle with it (a handle a restore lost, one the chain swept).
        var addressed: [HandleEntry] = []
        var dir: [String: HandleEntry]?
        do {
            let (d, at) = try await PrivacySession.handles.chainDirectoryRead()
            dir = d
            addressed = await w.reconcileHandle(d, readAt: at)
            handleDirectoryError = nil
        } catch {
            handleDirectoryError = describe(error)
        }
        publishPrivacy()
        let snap = w.snapshot
        handle = snap.handle
        handleEntry = snap.handle.isEmpty ? nil : dir?[snap.handle]
        addressedHandles = addressed
        caretakerExpiresAt = await w.caretakerExpiresAt()
        reminders = Reminders.due(Reminders.Inputs(
            now: Int64(Date().timeIntervalSince1970), identityLive: snap.identityStatus == .live, claimOpensAt: w.claimOpensAt(),
            claimedToday: w.claimedToday(), caretakerExpiresAt: caretakerExpiresAt, handle: snap.handle, handleEntry: handleEntry,
            addressed: addressed, ownAddress: w.address.encode()))
    }

    /// Settles moves in flight by their tx and retries recording confirmed ones in the new wallet.
    func checkMoves() async {
        guard let w = privacy else { return }
        await w.resolvePendingMoves()
        for p in w.outgoingMoves() where !p.recorded && !p.target.isEmpty {
            var inc = p
            inc.incoming = true; inc.target = ""; inc.recorded = true
            if (try? PrivacySession.Recorder(targetID: p.target).record(inc)) != nil { await w.markRecorded(p.txHash) }
        }
        publishPrivacy()
        await refreshPersonal()
    }

    /// The store id of the wallet at `index` and what it already holds.
    func switchTargetInfo(ofWallet index: Int) -> PrivacySession.TargetInfo? {
        guard let keys = try? privacyKeys(ofWallet: index) else { return nil }
        return PrivacySession.targetInfo(keys)
    }

    /// The recorder that writes moves into the wallet whose keys are `keys`.
    func moveRecorder(for keys: PrivacyKeys) -> PrivacyWallet.MoveRecorder { PrivacySession.Recorder(targetID: PrivacySession.storeID(keys)) }

    /// The handle directory's verdict on paying `input` ("@alice", "alice"):
    /// the whole directory, fresh, and the entry checked against the chain's own.
    func resolveHandle(_ input: String) async -> HandleDirectory.Resolution {
        do { return try await PrivacySession.handles.resolveForPayment(input) } catch {
            return .notPayable("Couldn't load the handle directory: \(describe(error))")
        }
    }

    func invalidateHandles() async { await PrivacySession.handles.invalidate() }

    /// The privacy keys of the wallet at `index` (another of this phone's
    /// wallets): what an identity switch names its moves to.
    func privacyKeys(ofWallet index: Int) throws -> PrivacyKeys {
        guard wallets.indices.contains(index) else { throw WalletStore.Error.notFound }
        return try PrivacyKeys.fromMnemonic(wallets[index].mnemonic)
    }


    /// x/assembly's open removal ballots, on their own: public and cheap, so
    /// the Govern tab re-reads them on every appearance and pull rather than
    /// waiting on a full privacy sync.
    func refreshRemovalBallots() async {
        if let ballots = try? await PrivacyQueries(rest: client.rest).removalBallots() { removalBallots = ballots }
    }

    /// The validator list (Query/Validators, read whole: every sync, every
    /// quote and every refresh): names, rates and who takes stake, for every
    /// validator alike. Never a query about one validator: it would tell the
    /// node which ones this wallet holds or is about to act on.
    func applyValidatorList(_ list: PrivacyReads.ValidatorList?) {
        guard let list else { return }
        let bonded = list.validators.filter(\.bonded)
        validators = bonded.map {
            Staking.Validator(operatorAddress: $0.validator, moniker: $0.moniker, tokens: String($0.tokens), commission: $0.commission)
        }
        stakeTargets = bonded.filter(\.delegatable).map(\.validator)
        validatorNames = Dictionary(list.validators.map { ($0.validator, (moniker: $0.moniker, commission: $0.commission)) }) { a, _ in a }
        derthRates = Dictionary(list.validators.map { ($0.validator, $0.rate) }) { a, _ in a }
    }

    /// `valoper`'s moniker, or its address when it has none.
    public func moniker(of valoper: String) -> String {
        let m = validatorNames[valoper]?.moniker ?? ""
        return m.isEmpty ? valoper : m
    }

    /// `valoper`'s commission (nil: not in the list).
    public func commission(of valoper: String) -> Double? { validatorNames[valoper]?.commission }

    public var totalStaked: BigInt {
        delegations.reduce(BigInt(0)) { $0 + (BigInt($1.amount) ?? 0) }
    }

    /// Stake on its way back out. Not spendable and not earning.
    public var unbondingTotal: BigInt {
        unbondings.reduce(BigInt(0)) { $0 + (BigInt($1.balance) ?? 0) }
    }

    public func pool(for token: Token) -> Dex.Pool? {
        pools.first { $0.tokenDenom == token.denom }
    }

    /// The LP-rewards option's share of the capital stream, which is half of
    /// what a pool pays. Zero until the govern tab has loaded it.
    public var lpOptionShare: Double = 0

    public func loadLPShare() async {
        let stream = await client.stream(.groundworks)
        guard let total = Double(stream.totalWeight), total > 0 else { return }
        // Matched on the handler rather than the description: a rename in
        // governance should not silently detach the APR from its source.
        let lp = stream.options
            .filter { $0.handler == "lp_rewards" }
            .compactMap { Double($0.amountAllocated) }
            .reduce(0, +)
        lpOptionShare = lp / total
    }

    /// What the unavailable screen says.
    func unavailableReason(_ error: Swift.Error) -> String {
        if case WalletStore.Error.noDeviceLock = error {
            return "Set a device passcode to use Earth Wallet. Your recovery phrase is stored behind it, and without one there is nothing to protect it with."
        }
        return describe(error)
    }

    /// Back to the start, for the unavailable screen's retry.
    public func retryLaunch() {
        phase = .launching
    }

    func describe(_ error: Swift.Error) -> String {
        switch error {
        case WalletStore.Error.authenticationFailed: "Authentication failed."
        case WalletStore.Error.notFound: "No wallet on this device."
        case WalletStore.Error.noDeviceLock:
            "Set a passcode on this device first. Your recovery phrase is stored behind it, and without one there is nothing to protect it with."
        case WalletStore.Error.invalidMnemonic: "That is not a valid recovery phrase."
        case WalletStore.Error.biometricsInvalidated:
            // Not a PIN fallback on a two-factor wallet: the vault is sealed
            // under PIN and prompt together, so the PIN alone opens nothing.
            // Android's UnlockGate says the same thing for the same reason.
            "\(WalletStore.biometryName) changed since this wallet was set up — a face or fingerprint was added or removed — so it can no longer open it. Restore the wallet from its recovery phrase."
        case WalletStore.Error.wrongPin: "Incorrect PIN."
        case WalletStore.Error.corrupt: "The stored wallet could not be read."
        // errSecMissingEntitlement: a build without Keychain access (an
        // unsigned simulator build) cannot hold a vault at all.
        case WalletStore.Error.keychain(-34018):
            "This build has no Keychain access, so it cannot store a wallet. Install a signed build."
        case let WalletStore.Error.keychain(status): "The Keychain refused to store the wallet (status \(status))."
        case _ where ChainErrors.explain(error) != nil: ChainErrors.explain(error)!
        case let EarthClient.Error.rejected(code, log): "Rejected (code \(code)): \(log)"
        case let EarthClient.Error.executionFailed(code, log): "Failed (code \(code)): \(log)"
        case let e as LocalizedError where e.errorDescription != nil: e.errorDescription!
        default: String(describing: error)
        }
    }
}
