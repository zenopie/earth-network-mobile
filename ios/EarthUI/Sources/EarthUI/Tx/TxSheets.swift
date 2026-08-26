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

    /// Whether this copy is the one showing something. The scrim keys off it
    /// rather than off each branch, so moving between confirm -> submitting ->
    /// result slides the card without flashing the dim behind it.
    private var presenting: Bool {
        tx.host == host && (tx.pending != nil || tx.submitting || tx.outcome != nil)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if presenting {
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .transition(.opacity)
            }

            if tx.host != host {
                EmptyView()
            } else if let details = tx.pending {
                TxConfirmSheet(details: details)
                    .transition(.move(edge: .bottom))
            } else if tx.submitting {
                TxSubmittingOverlay()
                    .transition(.move(edge: .bottom))
            } else if let outcome = tx.outcome {
                TxResultSheet(outcome: outcome)
                    .transition(.move(edge: .bottom))
            }
        }
        // Rises rather than fades, matching Android's ModalBottomSheet. A
        // spring rather than easeInOut because a card that travels the height
        // of a sheet reads as sticky on a linear curve; the low bounce is what
        // Material's own sheet does.
        .animation(.spring(response: 0.34, dampingFraction: 0.86), value: presenting)
        .animation(.spring(response: 0.34, dampingFraction: 0.86), value: tx.pending?.id)
        .animation(.spring(response: 0.34, dampingFraction: 0.86), value: tx.submitting)
        .animation(.spring(response: 0.34, dampingFraction: 0.86), value: tx.outcome?.id)
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
/// The container every transaction step draws into: a bottom sheet.
///
/// Ports `EarthSheet` from `ui/compose/EarthUi.kt`, which wraps Material's
/// `ModalBottomSheet` — full width against the bottom edge, only the top
/// corners rounded, `bgPrimary` behind it.
///
/// Not a SwiftUI `.sheet`. This is an overlay hosted wherever the request came
/// from, which is what lets a confirmation raised from inside another sheet
/// draw over it — a real `.sheet` presented from the root would land *behind*
/// whatever was already presented, which is the bug `TxController.Host` exists
/// to route around. The scrim is not here either: it belongs to `TxOverlay`, so
/// it can stay put while the card slides.
struct TxOverlayCard<Content: View>: View {
    @Environment(\.earth) private var theme
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: theme.space.x16) {
            // Material's sheet draws a drag handle, and without one this reads
            // as a card that happens to be at the bottom rather than as
            // something that came up from it.
            Capsule()
                .fill(theme.colors.strokePrimary)
                .frame(width: 36, height: 4)
                .padding(.top, theme.space.x8)

            VStack(alignment: .leading, spacing: theme.space.x16) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, theme.space.gutter)
        .padding(.bottom, theme.space.x32)
        .frame(maxWidth: .infinity)
        .background(
            // Top corners only. `.rect(cornerRadius:)` would round the bottom
            // two as well, which then float above the home indicator instead of
            // sitting on the edge.
            UnevenRoundedRectangle(
                topLeadingRadius: theme.space.radiusSheet,
                topTrailingRadius: theme.space.radiusSheet
            )
            .fill(theme.colors.bgPrimary)
            .ignoresSafeArea(edges: .bottom)
        )
    }
}

/// Shown while a transaction is in flight.
///
/// The broadcast waits for the block rather than returning at accept time, so
/// this is up for a couple of seconds and a caller that re-queries straight
/// after sees its own effect.
/// The wait between confirming and the chain answering.
///
/// Ports `TxPendingSheet` from `ui/compose/TxResultSheet.kt`, including where it
/// sits: the same bottom sheet as the confirmation and the result, so the three
/// are one position and three states. The result's badge then grows in over the
/// spinner instead of appearing from nowhere, which is the whole reason Android
/// put it there.
///
/// It used to be a small centred pill with a scrim of its own, which dimmed a
/// second time over `TxOverlay`'s and jumped from the bottom of the screen to
/// the middle and back for every transaction.
struct TxSubmittingOverlay: View {
    @Environment(\.earth) private var theme
    @Environment(TxController.self) private var tx

    var body: some View {
        TxOverlayCard {
            VStack(spacing: theme.space.x8) {
                // The web app's orbit loader, so the three clients wait the
                // same way. Smaller than the web's 160 — that is drawn across a
                // page, and this is most of the width of a sheet.
                OrbitLoader(diameter: 120)
                    .frame(maxWidth: .infinity)
                    .padding(.bottom, theme.space.x8)

                Text("Sending")
                    .font(EarthType.headline)
                    .foregroundStyle(theme.colors.textPrimary)

                Text("\(tx.lastAction ?? "It") is on its way to the chain. This takes a few seconds.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textSecondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, theme.space.x8)
        }
    }
}
