import SwiftUI

// The handful of pieces every screen needs. Deliberately small: SwiftUI already
// carries the general-purpose equivalents the Android side had to vendor —
// lists, sheets, buttons, dividers — so what is left here is Earth-shaped.

/// Uppercase eyebrow: "TOTAL BALANCE", "NETWORK FEE".
struct EarthLabel: View {
    @Environment(\.earth) private var theme
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased())
            .font(EarthType.eyebrow)
            .tracking(1.1)
            .foregroundStyle(theme.colors.textTertiary)
    }
}

/// A label/value line — fee, amount, balance after.
struct EarthDetailRow: View {
    @Environment(\.earth) private var theme
    let label: String
    let value: String
    var emphasis: Bool = false

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(EarthType.body)
                .foregroundStyle(theme.colors.textTertiary)
            Spacer(minLength: theme.space.x12)
            Text(value)
                .font(emphasis ? EarthType.title : EarthType.amount)
                .foregroundStyle(theme.colors.textPrimary)
                .multilineTextAlignment(.trailing)
        }
        .padding(.vertical, theme.space.x8)
    }
}

/// A chain error or a transaction hash.
///
/// Selectable, because the whole point of showing it is that it can be taken
/// somewhere else. This is the successor to a toast that truncated "out of gas
/// in location: ReadFlat; gasWanted: 400000, gasUsed: 400324" — the part that
/// mattered was the part it cut.
struct EarthCodeBlock: View {
    @Environment(\.earth) private var theme
    let text: String

    var body: some View {
        Text(text)
            .font(EarthType.mono)
            .foregroundStyle(theme.colors.textSecondary)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(theme.space.x12)
            .background(theme.colors.bgSecondary, in: .rect(cornerRadius: theme.space.radiusSm))
            .overlay {
                RoundedRectangle(cornerRadius: theme.space.radiusSm)
                    .strokeBorder(theme.colors.strokeSecondary, lineWidth: theme.space.stroke)
            }
    }
}

/// Transaction and registration state, as colour *and* word — the colour alone
/// says nothing to a third of men with a red-green deficiency.
enum EarthStatus { case success, pending, failed, neutral }

struct EarthStatusPill: View {
    @Environment(\.earth) private var theme
    let status: EarthStatus
    let text: String

    var body: some View {
        let (bg, fg): (Color, Color) = switch status {
        case .success: (theme.colors.accentTint, theme.colors.accentInk)
        case .pending: (theme.colors.warnTint, theme.colors.warnInk)
        case .failed: (theme.colors.errorTint, theme.colors.errorInk)
        case .neutral: (theme.colors.bgTertiary, theme.colors.textTertiary)
        }
        Text(text)
            .font(EarthType.eyebrow)
            .foregroundStyle(fg)
            .padding(.horizontal, theme.space.x12)
            .padding(.vertical, theme.space.x4)
            .background(bg, in: .capsule)
    }
}

/// A grouped block. One radius, one stroke, one background — the three things
/// every list of figures in this app sits on.
struct EarthCard<Content: View>: View {
    @Environment(\.earth) private var theme
    var padding: CGFloat?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: theme.space.x8) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding ?? theme.space.x16)
            .background(theme.colors.bgPrimary, in: .rect(cornerRadius: theme.space.radiusLg))
            .overlay {
                RoundedRectangle(cornerRadius: theme.space.radiusLg)
                    .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
            }
    }
}

/// The primary action. Never under the minimum touch target, and it takes the
/// full width because on every screen here there is one thing to do next.
struct EarthButton: View {
    @Environment(\.earth) private var theme
    @Environment(\.isEnabled) private var isEnabled
    let title: String
    var role: Role = .primary
    var busy = false
    let action: () -> Void

    enum Role { case primary, secondary, destructive }

    var body: some View {
        Button(action: action) {
            HStack(spacing: theme.space.x8) {
                if busy { ProgressView().controlSize(.small).tint(foreground) }
                Text(title).font(EarthType.label)
            }
            .frame(maxWidth: .infinity, minHeight: theme.space.buttonHeight)
            .foregroundStyle(foreground)
            .background(background, in: .rect(cornerRadius: theme.space.radiusMd))
            .overlay {
                if role == .secondary {
                    RoundedRectangle(cornerRadius: theme.space.radiusMd)
                        .strokeBorder(theme.colors.strokePrimary, lineWidth: theme.space.stroke)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(busy || !isEnabled)
        .opacity(isEnabled && !busy ? 1 : 0.5)
    }

    private var foreground: Color {
        switch role {
        case .primary: Palette.Base.bone
        case .secondary: theme.colors.textPrimary
        case .destructive: Palette.Base.bone
        }
    }

    private var background: Color {
        switch role {
        case .primary: Palette.Brand.b600
        case .secondary: theme.colors.bgPrimary
        case .destructive: Palette.Error.e600
        }
    }
}

/// A circular glyph on the accent tint — the leading element of every row in
/// the app that names a thing rather than a number.
struct EarthGlyph: View {
    @Environment(\.earth) private var theme
    let systemName: String
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(theme.colors.accentInk)
            .frame(width: size, height: size)
            .background(theme.colors.accentTint, in: .circle)
    }
}

/// What a screen shows when a query came back with nothing.
///
/// Distinguished from a failure on purpose: an empty pool list on a young chain
/// is a fact about the chain, and showing an error for it would be a lie.
struct EarthEmpty: View {
    @Environment(\.earth) private var theme
    let systemName: String
    let title: String
    var detail: String?

    var body: some View {
        VStack(spacing: theme.space.x8) {
            Image(systemName: systemName)
                .font(.system(size: 28))
                .foregroundStyle(theme.colors.textDisabled)
            Text(title)
                .font(EarthType.title)
                .foregroundStyle(theme.colors.textSecondary)
            if let detail {
                Text(detail)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, theme.space.x32)
    }
}

/// A section heading with optional trailing detail.
struct EarthSectionHeader: View {
    @Environment(\.earth) private var theme
    let title: String
    var trailing: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title)
                .font(EarthType.title)
                .foregroundStyle(theme.colors.textPrimary)
            Spacer()
            if let trailing {
                Text(trailing)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
    }
}

/// A screen background. Secondary rather than primary, so cards read as raised.
struct EarthBackground: ViewModifier {
    @Environment(\.earth) private var theme

    func body(content: Content) -> some View {
        content.background(theme.colors.bgSecondary.ignoresSafeArea())
    }
}

extension View {
    func earthBackground() -> some View { modifier(EarthBackground()) }
}
