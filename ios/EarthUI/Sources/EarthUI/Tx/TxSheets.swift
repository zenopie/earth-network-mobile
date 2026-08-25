import BigInt
import EarthCore
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The confirmation, the wait, and the result — in that order, over everything.
struct TxOverlay: View {
    @Environment(TxController.self) private var tx

    /// Which presentation context this copy is attached to. Several are
    /// mounted at once — the root and any sheet deep enough to need its own —
    /// and only the one the request named draws anything.
    let host: TxController.Host

    var body: some View {
        ZStack {
            if tx.host != host {
                EmptyView()
            } else if let details = tx.pending {
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

/// What you are about to sign, before anything is signed.
///
/// The whole reason `TxController` exists: every transaction in the app comes
/// through this sheet, so there is one place that shows the amount, the
/// destination, and the fee — and no screen can broadcast around it.
struct TxConfirmSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(TxController.self) private var tx

    let details: TxController.Details

    var body: some View {
        TxOverlayCard {
            Text(details.action)
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)

            EarthCard {
                ForEach(Array(details.rows.enumerated()), id: \.offset) { _, row in
                    EarthDetailRow(label: row.0, value: row.1)
                }
            }

            if !funded {
                // The gas gate's place. A new human has no ERTH and no account
                // on chain, so the fee cannot be paid — and this is the moment
                // that becomes true rather than a surprise at broadcast.
                GasWarning(awaitingGas: tx.awaitingGas)
            }

            HStack(spacing: theme.space.x12) {
                EarthButton(title: "Cancel", role: .secondary) { tx.cancel() }
                if funded {
                    // `confirm` clears `pending` itself, which is what takes
                    // this card away — there is no dismissal to coordinate.
                    EarthButton(title: "Confirm") { Task { await tx.confirm(in: model) } }
                } else {
                    EarthButton(title: tx.awaitingGas ? "Waiting for gas…" : "Watch an ad for gas") {
                        watchAd()
                    }
                }
            }
        }
    }

    /// Whether the balance covers this transaction's fee.
    ///
    /// Against the fee, not against zero. `model.hasGas` asks whether the
    /// account holds any ERTH at all, which says nothing about whether it holds
    /// enough: an account with 2,000 uerth cannot pay for a claim across two
    /// validators, and would have been shown a Confirm button that fails at
    /// broadcast.
    private var funded: Bool {
        guard let needed = BigInt(details.feeUerth) else { return true }
        return model.balance(.erth) >= needed
    }

    private func watchAd() {
        #if canImport(GoogleMobileAds) && os(iOS)
        guard !tx.awaitingGas, let host = UIApplication.shared.topViewController else { return }
        RewardedAds.show(from: host, walletAddress: model.address) { earned in
            // Earned means the ad completed, not that the dust landed: Google
            // calls the backend out of band and the send has to reach a block.
            // So the chain is polled rather than trusted to be ready.
            guard earned else { return }
            Task { await tx.awaitGas(in: model) }
        }
        #endif
    }
}

struct GasWarning: View {
    @Environment(\.earth) private var theme

    /// True once the ad has been watched and the grant is still in flight.
    var awaitingGas: Bool = false

    var body: some View {
        HStack(alignment: .top, spacing: theme.space.x8) {
            Image(systemName: "fuelpump.fill").foregroundStyle(theme.colors.warnInk)
            Text(awaitingGas
                ? "The gas hasn't arrived yet. Give it a moment."
                : "Not enough ERTH for the fee. Watch a short ad and we'll cover it.")
                .font(EarthType.bodySmall)
                .foregroundStyle(theme.colors.textSecondary)
        }
        .padding(theme.space.x12)
        .background(theme.colors.warnTint, in: .rect(cornerRadius: theme.space.radiusMd))
    }
}

/// What came back.
///
/// The failure case prints the chain's own words in full and selectable. That
/// is the successor to a toast that truncated "out of gas in location:
/// ReadFlat; gasWanted: 400000, gasUsed: 400324" — where the part it cut was
/// the part that explained everything.
struct TxResultSheet: View {
    @Environment(\.earth) private var theme
    @Environment(TxController.self) private var tx

    let outcome: TxController.Outcome

    var body: some View {
        TxOverlayCard {
            switch outcome {
            case let .succeeded(action, hash):
                header(status: .success, title: "\(action) confirmed", glyph: "checkmark.circle.fill")
                EarthLabel("Transaction")
                EarthCodeBlock(text: hash)
            case let .failed(action, reason):
                header(status: .failed, title: "\(action) failed", glyph: "xmark.circle.fill")
                EarthLabel("The chain said")
                EarthCodeBlock(text: reason)
            }
            EarthButton(title: "Done", role: .secondary) { tx.dismissOutcome() }
        }
    }

    private func header(status: EarthStatus, title: String, glyph: String) -> some View {
        HStack(spacing: theme.space.x12) {
            Image(systemName: glyph)
                .font(.system(size: 28))
                .foregroundStyle(status == .success ? theme.colors.accentInk : theme.colors.errorInk)
            Text(title)
                .font(EarthType.headline)
                .foregroundStyle(theme.colors.textPrimary)
        }
    }
}

/// A card floating over the app, with the scrim that makes it modal.
struct TxOverlayCard<Content: View>: View {
    @Environment(\.earth) private var theme
    @ViewBuilder var content: Content

    var body: some View {
        ZStack {
            Color.black.opacity(0.35).ignoresSafeArea()
            VStack(alignment: .leading, spacing: theme.space.x16) { content }
                .padding(theme.space.gutter)
                .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusSheet))
                .padding(theme.space.gutter)
        }
    }
}

/// Shown while a transaction is in flight.
///
/// The broadcast waits for the block rather than returning at accept time, so
/// this is up for a couple of seconds and a caller that re-queries straight
/// after sees its own effect.
struct TxSubmittingOverlay: View {
    @Environment(\.earth) private var theme

    var body: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            VStack(spacing: theme.space.x12) {
                ProgressView()
                Text("Waiting for the block")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
            }
            .padding(theme.space.x24)
            .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusLg))
        }
    }
}
