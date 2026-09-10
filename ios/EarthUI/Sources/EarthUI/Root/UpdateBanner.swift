import SwiftUI

/// "Version 1.2.0 is on the App Store" — tap to go there, or dismiss.
///
/// Sits under the top bar rather than over the content, because it is not
/// urgent enough to cover anything: it is information, and the app works
/// without acting on it. An overlay would also have to reason about the
/// sheets that Send, Stake and registration raise, which is a lot of
/// machinery for a line of text.
struct UpdateBanner: View {
    @Environment(\.earth) private var theme
    @Environment(\.openURL) private var openURL

    let available: AppUpdate.Available
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: theme.space.x12) {
            Image(systemName: "arrow.down.circle")
                .foregroundStyle(theme.colors.accentInk)

            VStack(alignment: .leading, spacing: theme.space.x2) {
                Text("Update available")
                    .font(EarthType.body)
                    .foregroundStyle(theme.colors.textPrimary)
                Text("Version \(available.version) is on the App Store.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }

            Spacer(minLength: 0)

            Button("Update") { openURL(available.storeURL) }
                .font(EarthType.body)
                .foregroundStyle(theme.colors.accentInk)

            Button {
                onDismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
            // The label is an unlabelled glyph, so the control needs one said
            // out loud; "xmark" is what VoiceOver would otherwise read.
            .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, theme.space.gutter)
        .padding(.vertical, theme.space.x12)
        .background(theme.colors.accentTint)
    }
}
