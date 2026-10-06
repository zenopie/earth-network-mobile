import SwiftUI

/// Glyphs drawn from the Android vector drawables.
///
/// The path data is copied verbatim out of `res/drawable/ic_swap_vertical.xml`.
/// It is drawn rather than rasterized because a flattened PNG loses its alpha
/// through QuickLook and comes back as a filled square, and because a vector
/// stays crisp and takes its colour from where it sits.
enum TabGlyphPaths {
    /// Two subpaths — the up arrow and the down arrow — concatenated. The
    /// reader closes each on its own `z`.
    static let swapVertical = """
M8,3c0.55,0 1,0.45 1,1v14.09l2.29,-2.3c0.39,-0.39 1.02,-0.39 1.41,0c0.39,0.39 0.39,1.02 0,1.41l-4,4c-0.39,0.39 -1.02,0.39 -1.41,0l-4,-4c-0.39,-0.39 -0.39,-1.02 0,-1.41c0.39,-0.39 1.02,-0.39 1.41,0L7,18.09V4C7,3.45 7.45,3 8,3z M16,21c-0.55,0 -1,-0.45 -1,-1V5.91l-2.29,2.3c-0.39,0.39 -1.02,0.39 -1.41,0c-0.39,-0.39 -0.39,-1.02 0,-1.41l4,-4c0.39,-0.39 1.02,-0.39 1.41,0l4,4c0.39,0.39 0.39,1.02 0,1.41c-0.39,0.39 -1.02,0.39 -1.41,0L17,5.91V20C17,20.55 16.55,21 16,21z
"""
}

/// A shape built from an SVG path string.
///
/// Only what these glyphs use: moves, lines, horizontal and vertical
/// lines, cubics, smooth cubics, and close — in both absolute and relative
/// form. No arcs, and no attempt at a general SVG reader; anything beyond the
/// subset is ignored rather than guessed at.
struct VectorGlyph: Shape {
    let pathData: String
    /// The drawable's viewport. Everything scales from here into the frame.
    var viewport: CGSize = CGSize(width: 24, height: 24)

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width / viewport.width, rect.height / viewport.height)
        let offset = CGPoint(
            x: rect.minX + (rect.width - viewport.width * scale) / 2,
            y: rect.minY + (rect.height - viewport.height * scale) / 2
        )

        var path = Path()
        var current = CGPoint.zero
        var start = CGPoint.zero
        // Where the previous cubic's second control point was, reflected for a
        // smooth curve. Absent unless the last command was a cubic.
        var lastControl: CGPoint?

        func place(_ p: CGPoint) -> CGPoint {
            CGPoint(x: offset.x + p.x * scale, y: offset.y + p.y * scale)
        }

        var tokens = Tokenizer(pathData)
        var command: Character = "M"

        while let next = tokens.next(command: &command) {
            let relative = command.isLowercase
            func point(_ x: Double, _ y: Double) -> CGPoint {
                relative ? CGPoint(x: current.x + x, y: current.y + y) : CGPoint(x: x, y: y)
            }

            switch Character(command.lowercased()) {
            case "m":
                current = point(next, tokens.number() ?? 0)
                start = current
                path.move(to: place(current))
                lastControl = nil
                // A second coordinate pair after a move is an implicit lineto.
                command = relative ? "l" : "L"

            case "l":
                current = point(next, tokens.number() ?? 0)
                path.addLine(to: place(current))
                lastControl = nil

            case "h":
                current = relative ? CGPoint(x: current.x + next, y: current.y)
                                   : CGPoint(x: next, y: current.y)
                path.addLine(to: place(current))
                lastControl = nil

            case "v":
                current = relative ? CGPoint(x: current.x, y: current.y + next)
                                   : CGPoint(x: current.x, y: next)
                path.addLine(to: place(current))
                lastControl = nil

            case "c":
                let c1 = point(next, tokens.number() ?? 0)
                let c2 = point(tokens.number() ?? 0, tokens.number() ?? 0)
                let end = point(tokens.number() ?? 0, tokens.number() ?? 0)
                path.addCurve(to: place(end), control1: place(c1), control2: place(c2))
                lastControl = c2
                current = end

            case "s":
                // The first control point mirrors the previous curve's second,
                // which is the whole point of the shorthand.
                let c1 = lastControl.map {
                    CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y)
                } ?? current
                let c2 = point(next, tokens.number() ?? 0)
                let end = point(tokens.number() ?? 0, tokens.number() ?? 0)
                path.addCurve(to: place(end), control1: place(c1), control2: place(c2))
                lastControl = c2
                current = end

            case "z":
                // Closed where it appears, not once at the end: a glyph with
                // two subpaths would otherwise join the second back to the
                // first's start and fill the space between them.
                path.closeSubpath()
                current = start

            default:
                break
            }
        }
        return path
    }

    /// Walks the string handing back numbers, remembering which command they
    /// belong to so a repeated coordinate list continues the last one.
    private struct Tokenizer {
        private let characters: [Character]
        private var index = 0

        init(_ text: String) { characters = Array(text) }

        mutating func next(command: inout Character) -> Double? {
            skipSeparators()
            while index < characters.count, characters[index].isLetter {
                let letter = characters[index]
                index += 1
                command = letter
                skipSeparators()
                // Close takes no numbers, so it is reported on its own and the
                // caller comes back for whatever follows.
                if letter == "z" || letter == "Z" { return 0 }
            }
            return number()
        }

        mutating func number() -> Double? {
            skipSeparators()
            guard index < characters.count else { return nil }
            var text = ""
            if characters[index] == "-" || characters[index] == "+" {
                text.append(characters[index]); index += 1
            }
            while index < characters.count,
                  characters[index].isNumber || characters[index] == "." {
                text.append(characters[index]); index += 1
            }
            return Double(text)
        }

        private mutating func skipSeparators() {
            while index < characters.count,
                  characters[index] == " " || characters[index] == "," || characters[index] == "\n" {
                index += 1
            }
        }
    }
}
