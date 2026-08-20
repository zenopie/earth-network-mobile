import EarthCore
import SwiftUI

/// The app shell.
///
/// Four tabs, because Earth has an axis a plain wallet does not: what this
/// wallet holds, and what the protocol does. Folding markets, allocations and
/// staking into a settings menu — which is what one home screen forces — would
/// bury half the chain.
public struct RootView: View {
    @State private var model = AppModel()
    @State private var tx = TxController()

    public init() {}

    public var body: some View {
        Group {
            switch model.phase {
            case .launching:
                ProgressView().task { model.start() }
            case .setup:
                SetupFlow()
            case .locked:
                UnlockScreen()
            case .ready:
                TabsView()
            }
        }
        // The overlay goes on *before* the environment, because an overlay is
        // a sibling of the view it decorates rather than a child of it — put
        // it after and it sits outside the environment these modifiers inject,
        // so it cannot see TxController and the app traps on first render.
        //
        // One confirmation and one result for the whole app, so no screen can
        // broadcast without them.
        //
        // Overlays rather than sheets, for two reasons. A screen that raises a
        // transaction is usually itself a sheet — Send, Stake — and asking
        // SwiftUI to present a sheet on an ancestor while a descendant is
        // dismissing does not reliably present anything. And a sheet's own
        // dismissal would have to be told apart from a confirmation, which is
        // the kind of distinction that silently stops working.
        .overlay { TxOverlay() }
        .environment(model)
        .environment(tx)
        .earthThemed()
    }
}

public enum Tab: String, CaseIterable, Hashable {
    case wallet, earn, swap, govern

    public var label: String { rawValue.capitalized }

    /// `-demoTab swap` opens on that tab. Simulator-only, and for the same
    /// reason `-demoWallet` exists: nothing can tap a simulator from the
    /// command line, so without it only one tab can ever be looked at.
    static var initialSelection: Tab {
        #if targetEnvironment(simulator)
        if let name = UserDefaults.standard.string(forKey: "demoTab"),
           let tab = Tab(rawValue: name) {
            return tab
        }
        #endif
        return .wallet
    }

    /// SF Symbols rather than the Android vectors.
    ///
    /// Those were drawn for a 24dp Compose viewport and traced into a path
    /// reader here; at 22pt the wallet lost its notch and the govern glyph read
    /// as a pie chart rather than people. The system set is drawn for these
    /// sizes, weights with the label beside it, and stays legible when the bar
    /// tints it.
    ///
    /// The action cards on the home screen keep the Android art — they are
    /// large, filled, and the two sets never appear side by side.
    var icon: String {
        switch self {
        case .wallet: "wallet.bifold"
        case .earn: "chart.line.uptrend.xyaxis"
        case .swap: "arrow.left.arrow.right"
        case .govern: "person.3"
        }
    }
}

struct TabsView: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @State private var settingsOpen = false
    @State private var liquidityOpen = false
    /// `-demoSheet settings` opens straight into one, for the same reason
    /// `-demoTab` exists: nothing can tap a simulator from the command line.
    @State private var demoSheet = TabsView.demoSheet

    enum DemoSheet: String, Identifiable {
        case settings, wallets, explorer
        var id: String { rawValue }
    }

    static var demoSheet: DemoSheet? {
        #if targetEnvironment(simulator)
        return UserDefaults.standard.string(forKey: "demoSheet").flatMap(DemoSheet.init)
        #else
        return nil
        #endif
    }

    var body: some View {
        VStack(spacing: 0) {
            EarthTopBar(
                // The Wallet tab is named for whose wallet it is; the other
                // tabs are named for what they do. "Wallet" over a balance
                // says nothing the balance does not.
                title: model.tab == .wallet ? model.walletName : model.tab.label,
                showsBalances: model.tab == .wallet || model.tab == .earn,
                // Tabs are destinations, not toolbars, so most have no action.
                // Swap has one because providing liquidity is adjacent to
                // swapping without being part of it — same market, different
                // thing to do with it.
                tabAction: model.tab == .swap
                    ? .init(icon: "drop", label: "Liquidity") { liquidityOpen = true }
                    : nil,
                onSettings: { settingsOpen = true }
            )
            Group {
                switch model.tab {
                case .wallet: WalletScreen()
                case .earn: EarnScreen()
                case .swap: SwapScreen()
                case .govern: GovernScreen()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            EarthTabBar(selection: Binding(get: { model.tab }, set: { model.tab = $0 }))
        }
        .background(theme.colors.bgPrimary.ignoresSafeArea())
        .task {
            model.tab = Tab.initialSelection
            await model.loadLPShare()
        }
        .sheet(isPresented: $settingsOpen) { SettingsSheet().earthThemed() }
        .sheet(item: $demoSheet) { sheet in
            switch sheet {
            case .settings: SettingsSheet().earthThemed()
            case .wallets: WalletsScreen().earthThemed()
            case .explorer: ExploreScreen().earthThemed()
            }
        }
        .sheet(isPresented: $liquidityOpen) { LiquiditySheet().earthThemed() }
    }
}

/// The main bar: which wallet you are in, and the way out to settings.
///
/// One wallet, so the left side identifies rather than switches — no chevron
/// implying a menu that does not exist.
struct EarthTopBar: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    /// Whether this tab shows any of your money.
    ///
    /// The eye belongs to the wallet, not to the app: on a tab with nothing of
    /// yours on screen, a control that hides nothing teaches you it does
    /// nothing.
    let title: String
    let showsBalances: Bool
    var tabAction: TabAction?
    let onSettings: () -> Void

    /// An action belonging to this tab, left of settings.
    struct TabAction {
        let icon: String
        let label: String
        let run: () -> Void

        init(icon: String, label: String, run: @escaping () -> Void) {
            self.icon = icon
            self.label = label
            self.run = run
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            if showsBalances {
                EarthAsset.logo?
                    .resizable()
                    .scaledToFit()
                    .frame(width: 32, height: 32)
                Spacer().frame(width: theme.space.x8)
            }
            // There is one wallet, so this identifies rather than switches —
            // no chevron implying a menu that does not exist.
            Text(title)
                .font(EarthType.header6)
                .fontWeight(.semibold)
                .foregroundStyle(theme.colors.textPrimary)
            Spacer()
            if showsBalances {
                Button { model.toggleBalances() } label: {
                    Image(systemName: model.balancesVisible ? "eye" : "eye.slash")
                        .font(.system(size: 18))
                        .foregroundStyle(theme.colors.textPrimary)
                        .frame(width: 40, height: 40)
                }
            }
            if let tabAction {
                Button(action: tabAction.run) {
                    Image(systemName: tabAction.icon)
                        .font(.system(size: 18))
                        .foregroundStyle(theme.colors.textPrimary)
                        .frame(width: 40, height: 40)
                }
                .accessibilityLabel(tabAction.label)
            }
            Button(action: onSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 18))
                    .foregroundStyle(theme.colors.textPrimary)
                    .frame(width: 40, height: 40)
            }
        }
        .padding(.horizontal, theme.space.x16)
        .padding(.vertical, theme.space.x8)
        .background(theme.colors.bgPrimary)
    }
}

/// The tab bar.
///
/// Deliberately not SwiftUI's TabView chrome: that arrives with its own
/// material, a tint role and a selection treatment, and bending those back
/// onto these tokens is more work than an HStack. A hairline above it instead
/// of a shadow, matching how the rest of the app separates surfaces.
///
/// Selection is carried by ink and weight together, not colour, so the current
/// tab survives a greyscale screenshot and a colour-blind reader.
struct EarthTabBar: View {
    @Environment(\.earth) private var theme
    @Binding var selection: Tab

    var body: some View {
        VStack(spacing: 0) {
            EarthDivider()
            HStack(spacing: 0) {
                ForEach(Tab.allCases, id: \.self) { tab in
                    let selected = tab == selection
                    Button {
                        selection = tab
                    } label: {
                        VStack(spacing: 3) {
                            Image(systemName: tab.icon)
                                .font(.system(size: 20, weight: selected ? .semibold : .regular))
                                .frame(height: 22)
                            Text(tab.label)
                                .font(EarthType.caption)
                                .fontWeight(selected ? .semibold : .regular)
                        }
                        .foregroundStyle(selected ? theme.colors.textPrimary : theme.colors.textTertiary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, theme.space.x2)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                }
            }
            // Top only. The home indicator already reserves room below, and
            // padding on top of a safe-area inset is the gap being complained
            // about — it reads as the bar floating rather than sitting on the
            // edge.
            .padding(.top, theme.space.x8)
            .padding(.bottom, theme.space.x2)
        }
        .background(theme.colors.bgPrimary)
    }
}

/// A tab's content: the screen gutter, a scroll, and pull to refresh.
///
/// No large title — the top bar identifies the wallet and the screens name
/// themselves in their first line, the way the Android app does.
struct EarthScreen<Content: View>: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space.x16) {
                if let error = model.lastError {
                    ErrorBanner(text: error)
                }
                content
            }
            .padding(.horizontal, theme.space.gutter)
            .padding(.top, theme.space.x24)
            .padding(.bottom, theme.space.x32)
        }
        .refreshable { await model.refresh() }
        .background(theme.colors.bgPrimary)
    }
}

/// A query failure, shown in place rather than as an alert.
///
/// A wallet that cannot reach the chain still has an address to show and a
/// phrase to back up. A modal would block both, and the chain being briefly
/// unreachable is the most ordinary failure there is.
struct ErrorBanner: View {
    @Environment(\.earth) private var theme
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: theme.space.x8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(theme.colors.warnInk)
            Text(text)
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(theme.space.x12)
        .background(theme.colors.warnTint, in: .rect(cornerRadius: theme.space.radiusMd))
    }
}
