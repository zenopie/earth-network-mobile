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
            Task { try? await adopt(mnemonic: phrase, name: "Wallet 1", pin: "0000") }
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
        guard !UnlockAttempts.status().lockedOut else { return false }

        do {
            // Stretching the PIN is deliberately slow — 200,000 rounds — so it
            // runs off the main actor. On the main thread it is a visible
            // freeze on every unlock and, because signing re-opens the vault,
            // on every transaction too.
            let store = self.store
            let wallets = try await Task.detached { try store.unlock(pin: pin) }.value
            guard !wallets.isEmpty else { throw WalletStore.Error.notFound }
            UnlockAttempts.recordSuccess()
            sessionPin = pin
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
            if case WalletStore.Error.wrongPin = error {
                UnlockAttempts.recordFailure()
            } else {
                lastError = describe(error)
            }
            return false
        }
    }

    /// The PIN, for the paths that need to read the vault again.
    ///
    /// Returns nil when locked, which is the only correct answer then.
    public var pin: String? { sessionPin }

    public func adopt(mnemonic: String, name: String, pin: String) async throws {
        try store.create(mnemonic: mnemonic, name: name, pin: pin)
        sessionPin = pin
        UnlockAttempts.recordSuccess()
        wallets = try store.unlock(pin: pin)
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
        let index = try store.add(mnemonic: mnemonic, name: name, pin: sessionPin)
        loadWallets()
        await select(index)
    }

    public func toggleBalances() {
        balancesVisible.toggle()
    }

    public func lock() {
        sessionPin = nil
        wallets = []
        phase = store.exists ? .locked : .setup
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
        case WalletStore.Error.wrongPin: "Incorrect PIN."
        case WalletStore.Error.corrupt: "The stored wallet could not be read."
        case let EarthClient.Error.rejected(code, log): "Rejected (code \(code)): \(log)"
        case let EarthClient.Error.executionFailed(code, log): "Failed (code \(code)): \(log)"
        default: String(describing: error)
        }
    }
}
