import SwiftUI

/// A figure as text, with `Figures.loading` drawn as three dots in a wave.
///
/// Built as one `Text` (the dots are characters whose opacity moves), so it
/// wraps, aligns on its baseline and takes the caller's font and colour like
/// any other text: "Balance …", "Voting … of … ERTH" animate in place. Only a
/// "…" standing as a word is a figure still loading; one inside a word
/// ("Syncing…", "erthz1ab…cd") is ordinary text.
struct FigureText: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        let parts = LoadingText.split(text)
        if parts.count == 1, case let .text(s) = parts[0] {
            Text(s)
        } else if reduceMotion {
            LoadingText.compose(parts) { _ in 0.5 }
                .accessibilityLabel(LoadingText.spoken(parts))
        } else {
            TimelineView(.animation) { context in
                let t = context.date.timeIntervalSinceReferenceDate
                LoadingText.compose(parts) { LoadingText.wave(t, dot: $0) }
            }
            .accessibilityLabel(LoadingText.spoken(parts))
        }
    }
}

/// Just the dots: a loading figure with nothing around it.
struct LoadingDots: View {
    var body: some View { FigureText(Figures.loading) }
}

enum LoadingText {
    enum Part: Equatable { case text(String), dots }

    /// `s` cut at each "…" that stands as a word.
    static func split(_ s: String) -> [Part] {
        let mark = Character(Figures.loading)
        var parts: [Part] = []
        var run = ""
        let chars = Array(s)
        for (i, c) in chars.enumerated() {
            let before = i == 0 || chars[i - 1] == " "
            let after = i == chars.count - 1 || chars[i + 1] == " "
            if c == mark, before, after {
                if !run.isEmpty { parts.append(.text(run)); run = "" }
                parts.append(.dots)
            } else {
                run.append(c)
            }
        }
        if !run.isEmpty || parts.isEmpty { parts.append(.text(run)) }
        return parts
    }

    /// One dot's opacity at `t`: a sine wave, each dot a fifth of a second
    /// behind the one before, so the light runs left to right.
    static func wave(_ t: TimeInterval, dot: Int) -> Double {
        let period = 1.2
        let phase = (t - Double(dot) * 0.18) / period * 2 * .pi
        return 0.25 + 0.75 * (0.5 + 0.5 * sin(phase))
    }

    static func compose(_ parts: [Part], opacity: (Int) -> Double) -> Text {
        parts.reduce(Text("")) { acc, part in
            switch part {
            case let .text(s): return acc + Text(s)
            case .dots: return acc + dots(opacity)
            }
        }
    }

    /// Three bullets, tightened to roughly the width of "…" so the line
    /// barely moves when the figure lands.
    private static func dots(_ opacity: (Int) -> Double) -> Text {
        (0..<3).reduce(Text("")) { acc, i in
            acc + Text("•").foregroundStyle(HierarchicalShapeStyle.primary.opacity(opacity(i)))
        }
        .tracking(-0.5)
    }

    static func spoken(_ parts: [Part]) -> String {
        parts.map { if case let .text(s) = $0 { s } else { "loading" } }.joined()
    }
}
