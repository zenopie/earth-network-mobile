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

    public enum Phase {
        /// Deciding whether there is a wallet at all.
        case launching
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
    public private(set) var registration: Personhood.RegistrationStatus = .none
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
    public private(set) var validators: [Staking.Validator] = []
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

    /// The PIN, held only while the app is unlocked.
    ///
    /// Android keeps the PIN for the session and decrypts on demand rather
    /// than keeping the phrase resident; this does the same. Locking drops it,
    /// and with it the ability to read anything.
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
            // A fixed PIN, since nothing can type one either.
            Task { try? await adopt(mnemonic: phrase, name: "Wallet 1", method: .pin, pin: "0000") }
            return
        }
        #endif
        phase = store.exists ? .locked : .setup
    }

    /// Unlock with the PIN.
    ///
    /// A wrong PIN is counted, because four digits is 10,000 combinations and
    /// the only thing making that a secret is how many guesses are allowed.
    public func unlock(pin: String) async -> Bool {
        guard !UnlockAttempts.status().lockedOut, !unlocking else { return false }
        unlocking = true
        defer { unlocking = false }

        do {
            let store = self.store
            let secret = try await unlockSecret(pin: pin, reason: "Unlock your Earth wallet")

            // Stretching is deliberately slow — 200,000 rounds — so it runs
            // off the main actor. On the main thread it is a visible freeze on
            // every unlock and, because signing re-opens the vault, on every
            // transaction too.
            let wallets = try await Task.detached { try store.unlock(pin: secret) }.value
            guard !wallets.isEmpty else { throw WalletStore.Error.notFound }
            UnlockAttempts.recordSuccess()
            sessionPin = secret
            self.wallets = wallets
            selected = min(UserDefaults.standard.integer(forKey: "selectedWallet"),
                           wallets.count - 1)
            address = wallets[selected].address
            walletName = wallets[selected].name
            lastError = nil
            phase = .ready
            await refresh()
            return true
        } catch {
            switch error {
            case WalletStore.Error.wrongPin:
                UnlockAttempts.recordFailure()
            case WalletStore.Error.authenticationFailed:
                // A dismissed prompt is not a wrong PIN, and counting it as
                // one would lock someone out for tapping cancel.
                break
            case WalletStore.Error.biometricsInvalidated:
                biometricsInvalidated = true
            default:
                lastError = describe(error)
            }
            return false
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
    public func unlockWithBiometrics() async -> Bool {
        guard method == .biometrics, !unlocking else { return false }
        unlocking = true
        defer { unlocking = false }

        do {
            let store = self.store
            let secret = try await unlockSecret(pin: nil, reason: "Unlock your Earth wallet")
            let wallets = try await Task.detached { try store.unlock(pin: secret) }.value
            guard !wallets.isEmpty else { throw WalletStore.Error.notFound }
            UnlockAttempts.recordSuccess()
            sessionPin = secret
            self.wallets = wallets
            selected = min(UserDefaults.standard.integer(forKey: "selectedWallet"),
                           wallets.count - 1)
            address = wallets[selected].address
            walletName = wallets[selected].name
            lastError = nil
            phase = .ready
            await refresh()
            return true
        } catch {
            // A cancelled prompt is not a failure worth reporting — the PIN
            // pad is still on screen behind it, and on a biometrics-only
            // wallet the button is still there to try again.
            if case WalletStore.Error.authenticationFailed = error { return false }
            if case WalletStore.Error.biometricsInvalidated = error {
                biometricsInvalidated = true
                return false
            }
            lastError = describe(error)
            return false
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
        wallets = try store.unlock(pin: secret)
        walletName = name
        selected = 0
        UserDefaults.standard.set(name, forKey: "walletName")
        UserDefaults.standard.set(0, forKey: "selectedWallet")
        address = try EarthKey(mnemonic: mnemonic).address
        phase = .ready
        await refresh()
    }

    public func forget() {
        store.delete()
        sessionPin = nil
        wallets = []
        biometricsInvalidated = false
        address = ""
        balances = [:]
        activity = nil
        registration = .none
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
        registration = .none
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
        sessionPin = nil
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
        case .active:
            defer { backgroundedAt = nil }
            guard phase == .ready, let away = backgroundedAt else { return }
            if ContinuousClock.now - away > Self.backgroundGrace { lock() }
        default:
            break
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
        async let registration = client.registrationStatus(address)
        async let pools = client.pools()
        async let fee = client.swapFeePercent()
        async let lpUnbonding = client.lpUnbondingSeconds()
        async let lpQueue = client.unbondings(address)
        async let validators = client.bondedValidators()
        async let delegations = client.delegations(address)
        async let unbondings = client.unbondingDelegations(address)
        async let rewards = client.totalRewards(address)
        async let bonded = client.totalBonded()
        async let transactions = client.transactions(for: address)

        self.balances = await balances.compactMapValues { BigInt($0) }
        self.registration = await registration
        self.pools = await pools
        self.swapFeePercent = Decimal(string: await fee) ?? self.swapFeePercent
        self.lpUnbondingSeconds = await lpUnbonding
        self.lpUnbondings = await lpQueue
        self.validators = await validators
        self.delegations = await delegations
        self.unbondings = await unbondings
        self.rewards = BigInt(await rewards) ?? 0
        self.totalBonded = BigInt(await bonded) ?? 0
        let signer = address
        self.activity = await transactions.compactMap { ActivityRow(tx: $0, self: signer) }
        await reachable
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

    /// LP shares held in one pool.
    ///
    /// Shares are an ordinary bank denom — `dexlp/<id>` — so they arrive with
    /// every other balance and need no query of their own.
    public func lpShares(poolID: UInt64) -> BigInt {
        balances[Dex.shareDenom(poolID: poolID)] ?? 0
    }

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

    public var isRegistered: Bool { registration.registered }

    public var canClaimAnml: Bool { Personhood.isAnmlClaimable(registration) }

    /// Gas the account can actually pay with. A new human has none of it, which
    /// is what the gas gate exists for.
    public var hasGas: Bool { balance(.erth) > 0 }

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
        case let EarthClient.Error.rejected(code, log): "Rejected (code \(code)): \(log)"
        case let EarthClient.Error.executionFailed(code, log): "Failed (code \(code)): \(log)"
        default: String(describing: error)
        }
    }
}
