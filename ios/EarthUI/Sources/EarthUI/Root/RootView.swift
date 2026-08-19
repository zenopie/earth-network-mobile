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
        .earthBackground()
    }
}

/// The confirmation, the wait, and the result — in that order, over everything.
struct TxOverlay: View {
    @Environment(TxController.self) private var tx

    var body: some View {
        ZStack {
            if let details = tx.pending {
                TxConfirmSheet(details: details)
                    .transition(.opacity)
            } else if tx.submitting {
                TxSubmittingOverlay()
                    .transition(.opacity)
            } else if let outcome = tx.outcome {
                TxResultSheet(outcome: outcome)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.15), value: tx.pending?.id)
        .animation(.easeInOut(duration: 0.15), value: tx.submitting)
        .animation(.easeInOut(duration: 0.15), value: tx.outcome?.id)
    }
}

struct TabsView: View {
    @Environment(AppModel.self) private var model
    @State private var selection = Tab.wallet

    enum Tab: Hashable { case wallet, earn, swap, govern }

    var body: some View {
        TabView(selection: $selection) {
            WalletScreen()
                .tabItem { Label("Wallet", systemImage: "wallet.bifold") }
                .tag(Tab.wallet)
            EarnScreen()
                .tabItem { Label("Earn", systemImage: "chart.line.uptrend.xyaxis") }
                .tag(Tab.earn)
            SwapScreen()
                .tabItem { Label("Swap", systemImage: "arrow.left.arrow.right") }
                .tag(Tab.swap)
            GovernScreen()
                .tabItem { Label("Govern", systemImage: "person.3") }
                .tag(Tab.govern)
        }
        .tint(Palette.Brand.b600)
        .task { await model.loadLPShare() }
    }
}

/// A screen: a large title, a refreshable scroll, and the error banner.
///
/// Every tab is the same shape, so the shape is written once — including
/// pull-to-refresh, which a wallet needs on every screen because the thing
/// being displayed changes without the app doing anything.
struct EarthScreen<Content: View>: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model

    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: theme.space.x16) {
                    if let error = model.lastError {
                        ErrorBanner(text: error)
                    }
                    content
                }
                .padding(.horizontal, theme.space.gutter)
                .padding(.bottom, theme.space.x32)
            }
            .refreshable { await model.refresh() }
            .navigationTitle(title)
            .earthBackground()
            .scrollContentBackground(.hidden)
        }
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
