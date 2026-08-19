import SwiftUI

/// Semantic colours, spacing, and type — the layer screens actually use.
///
/// Colours resolve per appearance the way `LightZashiColors` / `DarkZashiColors`
/// do on Android, so a screen asks for `.textSecondary` and gets the right one
/// in either mode rather than branching itself.
struct EarthTheme {
    let colors: EarthColors
    let space = EarthSpace()

    static func resolve(_ scheme: ColorScheme) -> EarthTheme {
        EarthTheme(colors: scheme == .dark ? .dark : .light)
    }
}

struct EarthColors {
    let bgPrimary: Color
    let bgSecondary: Color
    let bgTertiary: Color
    let strokePrimary: Color
    let strokeSecondary: Color
    let textPrimary: Color
    let textSecondary: Color
    let textTertiary: Color
    let textDisabled: Color
    let textError: Color

    /// The accent, and there is only one.
    ///
    /// Earth used to give each emission pillar a hue — ANML yellow, staking
    /// green, DEX teal, governance purple — so a row's pill said which pillar
    /// it belonged to before you read it. It made one screen look like four
    /// products stacked together, and bought a distinction nobody needed: the
    /// row already says "Staked" in words. One Sprout tint, one Sprout ink.
    let accentTint: Color
    let accentInk: Color

    /// Kept apart from the accent because a warning is not brand. An amber
    /// pulled toward green stops reading as a warning, which is the one thing
    /// it has to do.
    let warnTint: Color
    let warnInk: Color
    let errorTint: Color
    let errorInk: Color

    static let light = EarthColors(
        bgPrimary: Palette.Base.bone,
        bgSecondary: Palette.Base.concrete,
        bgTertiary: Palette.Gray.g100,
        strokePrimary: Palette.Gray.g200,
        strokeSecondary: Palette.Gray.g100,
        textPrimary: Palette.Base.obsidian,
        textSecondary: Palette.Gray.g800,
        textTertiary: Palette.Gray.g700,
        textDisabled: Palette.Gray.g400,
        textError: Palette.Error.e600,
        accentTint: Palette.Brand.b50,
        accentInk: Palette.Brand.b700,
        warnTint: Palette.Warning.w50,
        warnInk: Palette.Warning.w700,
        errorTint: Palette.Error.e50,
        errorInk: Palette.Error.e700
    )

    static let dark = EarthColors(
        bgPrimary: Palette.Base.obsidian,
        bgSecondary: Palette.SharkShade.dp06,
        bgTertiary: Palette.Shark.s800,
        strokePrimary: Palette.Shark.s700,
        strokeSecondary: Palette.Shark.s800,
        textPrimary: Palette.Shark.s50,
        textSecondary: Palette.Shark.s200,
        textTertiary: Palette.Shark.s300,
        textDisabled: Palette.Shark.s600,
        textError: Palette.Error.e300,
        accentTint: Palette.SharkShade.dp12,
        accentInk: Palette.Brand.b300,
        warnTint: Palette.Shark.s800,
        warnInk: Palette.Warning.w300,
        errorTint: Palette.Shark.s800,
        errorInk: Palette.Error.e300
    )
}

/// Spacing and radii on a 4pt grid, named by role rather than size — so a
/// screen asks for "the screen gutter", not "20".
struct EarthSpace {
    let x2: CGFloat = 2
    let x4: CGFloat = 4
    let x8: CGFloat = 8
    let x12: CGFloat = 12
    let x16: CGFloat = 16
    let x20: CGFloat = 20
    let x24: CGFloat = 24
    let x32: CGFloat = 32
    let x48: CGFloat = 48
    /// Screen gutter. Everything full-bleed stops here.
    let gutter: CGFloat = 20
    let radiusSm: CGFloat = 8
    let radiusMd: CGFloat = 12
    let radiusLg: CGFloat = 16
    let radiusSheet: CGFloat = 20
    let buttonHeight: CGFloat = 52
    let stroke: CGFloat = 1
}

/// The type scale.
///
/// The balance is the loudest thing in the app by a wide margin, which is most
/// of what makes a wallet feel simple: one number you cannot miss, everything
/// else stepping well back. Figures are monospaced-digit throughout — amounts
/// line up in columns, and a proportional digit makes a balance shimmer as it
/// updates.
enum EarthType {
    static let display = Font.system(size: 44, weight: .bold).monospacedDigit()
    static let headline = Font.system(size: 22, weight: .semibold)
    static let title = Font.system(size: 16, weight: .semibold)
    static let body = Font.system(size: 15)
    static let bodySmall = Font.system(size: 13.5)
    static let label = Font.system(size: 15, weight: .semibold)
    /// Uppercase eyebrows: "TOTAL BALANCE". Letter-spaced, never large.
    static let eyebrow = Font.system(size: 11, weight: .semibold)
    /// Chain errors and hashes.
    static let mono = Font.system(size: 12, design: .monospaced)
    static let amount = Font.system(size: 15, weight: .medium).monospacedDigit()
}

private struct EarthThemeKey: EnvironmentKey {
    static let defaultValue = EarthTheme(colors: .light)
}

extension EnvironmentValues {
    var earth: EarthTheme {
        get { self[EarthThemeKey.self] }
        set { self[EarthThemeKey.self] = newValue }
    }
}

/// Resolves the theme for the current appearance and puts it in the
/// environment, so no screen reads `colorScheme` itself.
struct EarthThemed: ViewModifier {
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content.environment(\.earth, EarthTheme.resolve(scheme))
    }
}

extension View {
    func earthThemed() -> some View { modifier(EarthThemed()) }
}
