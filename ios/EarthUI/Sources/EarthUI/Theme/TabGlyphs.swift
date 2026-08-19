import SwiftUI

/// The tab glyphs, drawn from the Android vector drawables.
///
/// The path data is copied verbatim out of `res/drawable/ic_home_*.xml`. They
/// are drawn rather than rasterized because a flattened PNG loses its alpha
/// through QuickLook and comes back as a filled square, and because a vector
/// stays crisp and takes its colour from the bar the way the originals do.
///
/// All four share a 24x24 viewport and stroke weight, so the bar reads as one
/// set.
enum TabGlyphPaths {
    static let receive = """
M12,3c0.83,0 1.5,0.67 1.5,1.5v11.38l3.44,-3.44c0.59,-0.59 1.54,-0.59 2.12,0c0.59,0.59 0.59,1.54 0,2.12l-6,6c-0.59,0.59 -1.54,0.59 -2.12,0l-6,-6c-0.59,-0.59 -0.59,-1.54 0,-2.12c0.59,-0.59 1.54,-0.59 2.12,0l3.44,3.44V4.5C10.5,3.67 11.17,3 12,3z
"""
    static let send = """
M12,21c-0.83,0 -1.5,-0.67 -1.5,-1.5V8.12l-3.44,3.44c-0.59,0.59 -1.54,0.59 -2.12,0c-0.59,-0.59 -0.59,-1.54 0,-2.12l6,-6c0.59,-0.59 1.54,-0.59 2.12,0l6,6c0.59,0.59 0.59,1.54 0,2.12c-0.59,0.59 -1.54,0.59 -2.12,0L13.5,8.12V19.5C13.5,20.33 12.83,21 12,21z
"""
    static let wallet = """
M4,5h13c1.66,0 3,1.34 3,3v0.5h-3.5c-1.93,0 -3.5,1.57 -3.5,3.5s1.57,3.5 3.5,3.5H20v0.5c0,1.66 -1.34,3 -3,3H4c-1.1,0 -2,-0.9 -2,-2V7C2,5.9 2.9,5 4,5zM16.5,10.5H21c0.55,0 1,0.45 1,1v1c0,0.55 -0.45,1 -1,1h-4.5c-0.83,0 -1.5,-0.67 -1.5,-1.5S15.67,10.5 16.5,10.5z
"""
    static let earn = """
M20.5,4h-5c-0.83,0 -1.5,0.67 -1.5,1.5S14.67,7 15.5,7h1.38l-5.38,5.38l-2.44,-2.44c-0.59,-0.59 -1.54,-0.59 -2.12,0l-4,4c-0.59,0.59 -0.59,1.54 0,2.12c0.59,0.59 1.54,0.59 2.12,0L8,13.12l2.44,2.44c0.59,0.59 1.54,0.59 2.12,0L19,9.12V10.5c0,0.83 0.67,1.5 1.5,1.5S22,11.33 22,10.5v-5C22,4.67 21.33,4 20.5,4z
"""
    static let swap = """
M8.06,3.44c0.59,0.59 0.59,1.54 0,2.12L6.62,7H16.5C17.33,7 18,7.67 18,8.5S17.33,10 16.5,10H6.62l1.44,1.44c0.59,0.59 0.59,1.54 0,2.12c-0.59,0.59 -1.54,0.59 -2.12,0l-4,-4c-0.59,-0.59 -0.59,-1.54 0,-2.12l4,-4C6.52,2.85 7.48,2.85 8.06,3.44zM15.94,10.44c0.59,-0.59 1.54,-0.59 2.12,0l4,4c0.59,0.59 0.59,1.54 0,2.12l-4,4c-0.59,0.59 -1.54,0.59 -2.12,0c-0.59,-0.59 -0.59,-1.54 0,-2.12L17.38,17H7.5C6.67,17 6,16.33 6,15.5S6.67,14 7.5,14h9.88l-1.44,-1.44C15.35,11.98 15.35,11.02 15.94,10.44z
"""
    static let govern = """
M11,3.06C6.5,3.55 3,7.37 3,12c0,4.97 4.03,9 9,9c4.63,0 8.45,-3.5 8.94,-8H13c-1.1,0 -2,-0.9 -2,-2V3.06zM13,3.06V9h5.94C18.5,5.94 16.06,3.5 13,3.06z
"""
}

/// A shape built from an SVG path string.
///
/// Only what these four icons use: moves, lines, horizontal and vertical
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

            default:
                break
            }
        }

        if pathData.lowercased().contains("z") {
            path.closeSubpath()
            current = start
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
                if letter == "z" || letter == "Z" { skipSeparators(); continue }
                command = letter
                skipSeparators()
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
