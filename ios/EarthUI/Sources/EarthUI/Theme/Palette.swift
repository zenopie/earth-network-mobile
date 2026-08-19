import SwiftUI

/// The raw ramps, ported from `ui/vendor/theme/colors/ZashiColorPalette.kt`.
///
/// The Android app vendors Zashi's design system and re-skins its palette to
/// the Sprout ramps — Zcash gold becomes Earth green, and the warm olive
/// neutrals become green-biased ones. Only the ramps come across here: 16,000
/// lines of Compose components have no SwiftUI equivalent worth transliterating,
/// but the colours are what make the two apps look like one product.
enum Palette {

    static func hex(_ value: UInt32) -> Color {
        Color(
            .sRGB,
            red: Double((value >> 16) & 0xff) / 255,
            green: Double((value >> 8) & 0xff) / 255,
            blue: Double(value & 0xff) / 255,
            opacity: 1
        )
    }

    enum Base {
        static let bone = hex(0xFFFFFF)
        static let concrete = hex(0xF7F9F6)
        static let obsidian = hex(0x0F120E)
        static let brand = hex(0x00C244)
    }

    /// Neutrals for light mode.
    enum Gray {
        static let g50 = hex(0xF7F9F6)
        static let g100 = hex(0xEDF1EA)
        static let g200 = hex(0xDCE3D8)
        static let g300 = hex(0xC3CCBE)
        static let g400 = hex(0xAAB4A5)
        static let g500 = hex(0x8B9587)
        static let g600 = hex(0x727C6D)
        static let g700 = hex(0x5A6356)
        static let g800 = hex(0x3C443A)
        static let g900 = hex(0x262B24)
    }

    /// Neutrals for dark mode. A separate ramp, not the grays inverted.
    enum Shark {
        static let s50 = hex(0xF1F3EF)
        static let s100 = hex(0xE3E7E0)
        static let s200 = hex(0xCDD3C9)
        static let s300 = hex(0xB0B8AB)
        static let s400 = hex(0x939C8E)
        static let s600 = hex(0x5E6857)
        static let s700 = hex(0x474F42)
        static let s800 = hex(0x31372E)
        static let s900 = hex(0x1D211B)
    }

    /// Elevation shades for dark mode surfaces.
    enum SharkShade {
        static let dp06 = hex(0x23291E)
        static let dp12 = hex(0x2C3427)
    }

    enum Brand {
        static let b25 = hex(0xF2FDF5)
        static let b50 = hex(0xE4FBEA)
        static let b100 = hex(0xC4F6D2)
        static let b300 = hex(0x5FE087)
        static let b500 = hex(0x00C244)
        static let b600 = hex(0x00A238)
        static let b700 = hex(0x00822D)
    }

    enum Error {
        static let e50 = hex(0xFDECEA)
        static let e300 = hex(0xF7B8B1)
        static let e600 = hex(0xD93025)
        static let e700 = hex(0xB4231A)
    }

    enum Warning {
        static let w50 = hex(0xFEF0C7)
        static let w300 = hex(0xFEC84B)
        static let w700 = hex(0x93370D)
    }
}
