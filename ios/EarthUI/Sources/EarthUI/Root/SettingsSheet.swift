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
        case identity, handle, notes, wallets, security, notifications, network, explorer, about
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
                        row("Merge notes", "square.stack.3d.down.right", subtitle: "Combine small private notes", route: .notes)
                        divider
                        row("Wallets", "wallet.bifold", subtitle: model.walletName, route: .wallets)
                        divider
                        row("Unlocking", "lock", subtitle: methodName, route: .security)
                        divider
                        row("Notifications", "bell", subtitle: "ANML ready, new proposals", route: .notifications)
                        divider
                        row("Network", "network", subtitle: NodeSettings.current.isDefault ? "Earth's node" : "Your node", route: .network)
                        divider
                        row("Explorer", "safari",
                            subtitle: "Blocks, validators and registrations", route: .explorer)
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

                Text("Version \(AppVersion.text)")
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
                case .notifications: NotificationsScreen().earthThemed()
                case .network: NetworkScreen().earthThemed()
                case .explorer: ExploreScreen().earthThemed()
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
                VStack(spacing: 0) {
                    Spacer().frame(height: theme.space.x32)
                    Image(systemName: model.isRegistered ? "checkmark.shield.fill" : "person.crop.circle.badge.questionmark")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(model.isRegistered ? theme.colors.accentInk : theme.colors.textTertiary)
                        .frame(width: 72, height: 72)
                        .background(model.isRegistered ? theme.colors.accentTint : theme.colors.bgTertiary, in: .circle)
                    Spacer().frame(height: theme.space.x16)
                    Text(model.isRegistered ? "Verified human" : ended ? "Registration ended" : "Not verified")
                        .font(EarthType.header6).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text(line)
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textTertiary)
                        .padding(.top, theme.space.x4)

                    Spacer().frame(height: theme.space.x32)
                    VStack(spacing: theme.space.x12) {
                        if model.isRegistered {
                            EarthPillButton(title: model.handle.isEmpty ? "Claim a handle" : "@\(model.handle)") { handleOpen = true }
                            EarthPillButton(title: "Switch identity", role: .secondary) { switching = true }
                            if let moveOffer, moveOffer.anything {
                                MoveOfferCard(offer: moveOffer) { self.moveOffer = await model.moveOffer() }
                            }
                        } else if ended {
                            // The chain refuses an identity it registered before (used_idcs), so a
                            // renewal registers this wallet's next identity from the same phrase;
                            // the previous one's handle and vote move over afterwards.
                            EarthPillButton(title: "Renew") { registering = true }
                        } else {
                            EarthPillButton(title: "Verify with passport") { registering = true }
                        }
                    }
                }
                .padding(.horizontal, theme.space.gutter)
                .frame(maxWidth: .infinity)
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
        }
    }

    /// Registered here before, and no longer live: renewing, not registering.
    private var ended: Bool { !model.isRegistered && model.privacy?.registeredBefore() == true }

    private var line: String {
        if model.isRegistered { return "Private · not linked to this wallet" }
        return ended ? "Renew to claim ANML and vote" : "Verify to claim ANML and vote"
    }
}

/// "1.0 (32)", from the bundle rather than written into a view.
enum AppVersion {
    static var text: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String
        return build.map { "\(version) (\($0))" } ?? version
    }
}

struct AboutScreen: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer().frame(height: theme.space.x32)
                    EarthAsset.logo?
                        .resizable().scaledToFit()
                        .frame(width: 72, height: 72)
                    Spacer().frame(height: theme.space.x16)
                    Text("Earth Wallet")
                        .font(EarthType.header6).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textPrimary)
                    Text("Version \(AppVersion.text)")
                        .font(EarthType.body)
                        .foregroundStyle(theme.colors.textTertiary)
                        .padding(.top, theme.space.x4)

                    Spacer().frame(height: theme.space.x32)
                    VStack(spacing: 0) {
                        link("Privacy policy", "https://erth.network/privacy-policy")
                        EarthDivider()
                        link("Source", "https://github.com/zenopie/earth-network-mobile")
                    }
                }
                .padding(.horizontal, theme.space.gutter)
                .frame(maxWidth: .infinity)
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
