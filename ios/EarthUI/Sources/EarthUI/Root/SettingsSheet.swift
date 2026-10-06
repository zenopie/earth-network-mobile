import EarthCore
import SwiftUI

/// Settings.
///
/// A plain scrolling list of rows separated by dividers, then the version
/// pinned to the bottom. No cards and no section headers — a settings screen is
/// scanned for one row, and grouping boxes slow that down.
struct SettingsSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var route: Route?

    enum Route: String, Identifiable {
        case identity, handle, notes, wallets, security, network, explorer, activity, about
        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 0) {
                        row("Identity", "checkmark.shield",
                            // Null, not "Not registered", while the wallet is
                            // still loading: falling back to the empty state
                            // asserts a fact about a wallet nothing has been
                            // read about yet.
                            subtitle: model.isRegistered ? "Verified human" : "Not registered",
                            route: .identity)
                        divider
                        row("Handle", "at", subtitle: model.handle.isEmpty ? "Claim a name others can pay" : "@\(model.handle)", route: .handle)
                        divider
                        row("Shielded notes", "lock.shield", subtitle: "Shield, merge small notes", route: .notes)
                        divider
                        row("Wallets", "wallet.bifold", subtitle: model.walletName, route: .wallets)
                        divider
                        row("Unlocking", "lock", subtitle: methodName, route: .security)
                        divider
                        row("Network", "network", subtitle: NodeSettings.current.isDefault ? "Earth's node" : "Your node", route: .network)
                        divider
                        row("Explorer", "safari",
                            subtitle: "Blocks, validators and registrations", route: .explorer)
                        divider
                        row("Activity", "list.bullet", route: .activity)
                        divider
                        row("About", "info.circle", route: .about)
                    }
                    .padding(.horizontal, theme.space.x4)
                }

                EarthButton(title: "Lock", role: .secondary) {
                    model.lock()
                    dismiss()
                }
                .padding(.horizontal, theme.space.gutter)

                Text("Version 0.1.0")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .padding(.vertical, theme.space.x24)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .sheet(item: $route) { destination in
                switch destination {
                case .identity: IdentityScreen().earthThemed()
                case .handle: HandleScreen().earthThemed()
                case .notes: NotesScreen().earthThemed()
                case .wallets: WalletsScreen().earthThemed()
                case .security: SecurityScreen().earthThemed()
                case .network: NetworkScreen().earthThemed()
                case .explorer: ExploreScreen().earthThemed()
                case .activity: ActivityScreen().earthThemed()
                case .about: AboutScreen().earthThemed()
                }
            }
        }
    }

    private var methodName: String {
        switch model.method {
        case .pin: "PIN"
        case .biometrics: WalletStore.biometryName
        case .both: "\(WalletStore.biometryName) and PIN"
        }
    }

    private var divider: some View {
        EarthDivider().padding(.horizontal, theme.space.x4)
    }

    private func row(_ title: String, _ icon: String, subtitle: String? = nil, route: Route) -> some View {
        Button { self.route = route } label: {
            HStack(spacing: theme.space.x12) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundStyle(theme.colors.textPrimary)
                    .frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 0) {
                    Text(title)
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textPrimary)
                    if let subtitle {
                        Text(subtitle)
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                    }
                }
                Spacer()
                Text("›")
                    .font(EarthType.textLg)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            .padding(.horizontal, theme.space.x16)
            .padding(.vertical, theme.space.x12)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// Whether *this wallet* is a verified human — a different question from how
/// many the network has, which is on the explorer.
///
/// Private: the registration's link to this wallet exists only here. The
/// chain holds an identity leaf nobody can attribute, and every claim, vote,
/// caretaker split and handle proves membership without saying
/// which leaf.
struct IdentityScreen: View {
    @State private var handleOpen = false
    @State private var switching = false
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx
    @Environment(\.dismiss) private var dismiss
    @State private var registering = false
    @State private var adding = false
    /// After a switch: what the identity this one replaced can bring here (it syncs that wallet).
    @State private var moveOffer: AppModel.MoveOffer?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    EarthListRow(
                        initial: model.isRegistered ? "✓" : "?",
                        name: model.isRegistered ? "Verified human" : "Not registered",
                        subtitle: subtitle,
                        badgeBackground: model.isRegistered ? theme.colors.accentTint : theme.colors.bgSecondary,
                        badgeForeground: model.isRegistered ? theme.colors.accentInk : theme.colors.textTertiary
                    )

                    Text("The proof shows a government signed your document and that you have not registered before. It does not carry your name, your photo, or your document number, and nothing about the passport leaves this phone. Your registration is not linked to this wallet on chain: claims and votes prove you are a registered person without saying which.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)

                    if model.isRegistered {
                        EarthDetailRow(label: "Handle", value: model.handle.isEmpty ? "None" : "@\(model.handle)")
                        EarthButton(title: model.handle.isEmpty ? "Claim a handle" : "Manage @\(model.handle)", role: .secondary) { handleOpen = true }
                        if let moveOffer, moveOffer.anything {
                            MoveOfferCard(offer: moveOffer) { self.moveOffer = await model.moveOffer() }
                        }
                        Text("Your registration stays with this wallet until it expires. To move it to another wallet, switch identity: register the same passport there, then bring your handle and caretaker vote over from the new wallet. Nothing is paid the second time. A passport can switch once per day (UTC).")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                        EarthButton(title: "Switch identity", role: .secondary) { switching = true }
                        // Transparent fees (an LP, IBC, a contract call) come
                        // from the public account: unshield ERTH there. The
                        // membership-proved transparent gas grant is gone from
                        // the chain and the backend.
                        Text("Need ERTH in your public account for fees? Unshield some from Portfolio.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                    } else if model.privacy?.identityUsed() == true {
                        // The chain keeps every identity it ever registered (used_idcs) and
                        // refuses it again (1130): a return after a switch or a lapse is a new wallet.
                        Text("Each identity can register only once. To count as a person again, create a new wallet and register your passport there. If this passport's registration is still live in another wallet, registering in the new one switches it there.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textSecondary)
                        EarthButton(title: "Create a new wallet") { adding = true }
                    } else {
                        EarthButton(title: "Register with your passport") { registering = true }
                    }
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("Identity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
            .sheet(isPresented: $registering) { RegistrationSheet().earthThemed() }
            .sheet(isPresented: $adding) { AddWalletSheet(mode: .create).earthThemed() }
            .sheet(isPresented: $handleOpen) { HandleScreen().earthThemed() }
            .sheet(isPresented: $switching) { SwitchIdentityScreen().earthThemed() }
            .task(id: model.isRegistered) { moveOffer = model.isRegistered ? await model.moveOffer() : nil }
            // A sheet over the settings sheet, so the root's confirmation
            // would draw behind both. See TxController.Host.
            .overlay { TxOverlay(host: .identity) }
        }
    }

    private var subtitle: String {
        switch model.identityStatus {
        case .live: "Your registration is in the identity tree."
        case .zeroed: "Your registration expired, or moved to another wallet. This wallet's identity cannot register again: register from a new wallet to take part."
        case .none: "Prove you are a unique human to claim ANML and vote."
        }
    }
}

struct AboutScreen: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    Text("Earth Wallet is a self-custody wallet for earth-1, a chain whose premise is proof of personhood: you prove you are a unique human by reading your passport's chip and generating a zero-knowledge proof on this device. No custodian, and no server sees the passport.")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textSecondary)

                    VStack(spacing: 0) {
                        link("Privacy policy", "https://erth.network/privacy")
                        EarthDivider()
                        link("Terms", "https://erth.network/terms")
                        EarthDivider()
                        link("Source", "https://github.com/zenopie/earth-network-mobile")
                    }

                    Text("Version 0.1.0")
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textTertiary)
                }
                .padding(theme.space.gutter)
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }

    private func link(_ title: String, _ url: String) -> some View {
        EarthRow(title: title, action: { URL(string: url).map { openURL($0) } })
    }
}

/// The full activity list. The wallet screen shows the same rows, trimmed.
struct ActivityScreen: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let activity = model.activity, !activity.isEmpty {
                        ForEach(activity) { ActivityItem(row: $0) }
                    } else {
                        Text("Nothing yet. Transactions appear here once they are confirmed.")
                            .font(EarthType.bodySmall)
                            .foregroundStyle(theme.colors.textTertiary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(theme.space.gutter)
                    }
                }
            }
            .refreshable { await model.refresh() }
            .navigationTitle("Activity")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .background(theme.colors.bgPrimary)
            .scrollContentBackground(.hidden)
        }
    }
}
