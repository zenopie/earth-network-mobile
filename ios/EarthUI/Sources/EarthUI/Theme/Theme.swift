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

    /// Buttons, as the vendored palette defines them. Secondary is a filled
    /// light green with dark green ink — *not* an outlined white button. The
    /// secondary fill on Android is Brand.100 and its border token is
    /// deliberately unspecified.
    let brandButtonBg: Color
    let brandButtonFg: Color
    let secondaryButtonBg: Color
    let secondaryButtonFg: Color
    let buttonDisabledBg: Color
    let buttonDisabledFg: Color

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
        brandButtonBg: Palette.Brand.b500,
        brandButtonFg: Palette.Base.bone,
        secondaryButtonBg: Palette.Brand.b100,
        secondaryButtonFg: Palette.Brand.b800,
        buttonDisabledBg: Palette.Gray.g100,
        buttonDisabledFg: Palette.Gray.g500,
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
        brandButtonBg: Palette.Brand.b500,
        brandButtonFg: Palette.Base.obsidian,
        secondaryButtonBg: Palette.SharkShade.dp12,
        secondaryButtonFg: Palette.Brand.b300,
        buttonDisabledBg: Palette.Shark.s800,
        buttonDisabledFg: Palette.Shark.s600,
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
    /// Buttons and fields. Android's EarthButtonDefaults.shape.
    let radiusMd: CGFloat = 12
    let radiusLg: CGFloat = 16
    let radiusSheet: CGFloat = 20
    let buttonHeight: CGFloat = 52
    let stroke: CGFloat = 1
}

/// The type scale, matching the vendored one on Android point for point.
///
/// The sizes are not a re-derivation: `header1` really is 56 at regular weight,
/// and the eyebrows really are 14 rather than a small letter-spaced caption.
/// The balance being enormous *and* unbolded is most of what makes the Android
/// wallet feel calm, and a semibold 44 reads as a different product.
enum EarthType {
    /// The balance, and nothing else. 56/68 regular.
    static let display = Font.system(size: 56, weight: .regular).monospacedDigit()
    /// Screen headings outside the app shell — setup, unlock. 28/40.
    static let headline = Font.system(size: 28, weight: .regular)
    /// The balance. 48/60 — set semibold at the call site.
    static let header2 = Font.system(size: 48).monospacedDigit()
    /// The wallet name in the top bar. 24/32.
    static let header6 = Font.system(size: 24)
    static let textXl = Font.system(size: 20)
    static let textLg = Font.system(size: 18)
    /// The workhorse: row titles, values, body copy. 16/24.
    static let body = Font.system(size: 16)
    /// Eyebrows and secondary lines. 14/20 — the same size as Android's
    /// textSm, uppercased at the call site rather than letter-spaced.
    static let bodySmall = Font.system(size: 14)
    /// Tab labels. 12/16.
    static let caption = Font.system(size: 12)
    static let label = Font.system(size: 16, weight: .semibold)
    /// Chain errors and hashes.
    static let mono = Font.system(size: 12, design: .monospaced)
    /// Figures in a column. Monospaced digits so they line up and so a
    /// refreshing balance does not shimmer.
    static let amount = Font.system(size: 16).monospacedDigit()
    static let title = Font.system(size: 16, weight: .semibold)
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
