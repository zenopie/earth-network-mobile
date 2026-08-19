import EarthCore
import SwiftUI

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

            if !model.hasGas {
                // The gas gate's place. A new human has no ERTH and no account
                // on chain, so the fee cannot be paid — and this is the moment
                // that becomes true rather than a surprise at broadcast.
                GasWarning()
            }

            HStack(spacing: theme.space.x12) {
                EarthButton(title: "Cancel", role: .secondary) { tx.cancel() }
                // `confirm` clears `pending` itself, which is what takes this
                // card away — there is no dismissal to coordinate with.
                EarthButton(title: "Confirm") { Task { await tx.confirm(in: model) } }
            }
        }
    }
}

struct GasWarning: View {
    @Environment(\.earth) private var theme

    var body: some View {
        HStack(alignment: .top, spacing: theme.space.x8) {
            Image(systemName: "fuelpump.fill").foregroundStyle(theme.colors.warnInk)
            Text("This account holds no ERTH, so the fee cannot be paid. The rewarded-ad gas grant is not built yet on iOS.")
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
