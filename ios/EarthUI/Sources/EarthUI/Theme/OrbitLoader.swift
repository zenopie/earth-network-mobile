import SwiftUI

/// ANML orbiting ERTH.
///
/// Ports the web app's `OrbitLoader` (`src/components/Layout.jsx` and the orbit
/// rules in `Layout.css`) so the three clients wait the same way. Its geometry
/// is the web's, in points rather than pixels:
///
///     container   160
///     track       140, 2px dashed, the ink at 10%
///     ERTH         80, centred
///     ANML         40, on the track, 2.5s linear, counter-rotated
///
/// The counter-rotation is the part that is easy to leave out and obvious when
/// missing: without it the ANML coin tumbles as it goes round instead of
/// staying upright, and a spinning coin reads as a second animation rather than
/// as one thing travelling.
struct OrbitLoader: View {
    @Environment(\.earth) private var theme

    /// Scales the whole thing. The web draws it at 160 across a page; on a
    /// sheet that is most of the width, so callers size it down.
    var diameter: CGFloat = 160

    @State private var angle: Double = 0

    private var scale: CGFloat { diameter / 160 }
    private var track: CGFloat { 140 * scale }
    private var erth: CGFloat { 80 * scale }
    private var anml: CGFloat { 40 * scale }

    var body: some View {
        ZStack {
            Circle()
                .strokeBorder(
                    theme.colors.textPrimary.opacity(0.1),
                    style: StrokeStyle(lineWidth: 2, dash: [4, 4])
                )
                .frame(width: track, height: track)

            EarthAsset.erth?
                .resizable()
                .scaledToFit()
                .frame(width: erth, height: erth)
                .clipShape(.circle)

            // The orbit: place the coin on the track's edge, rotate the pair,
            // then turn the coin back by the same amount so it stays upright.
            EarthAsset.anml?
                .resizable()
                .scaledToFit()
                .frame(width: anml, height: anml)
                .clipShape(.circle)
                .rotationEffect(.degrees(-angle))
                .offset(x: track / 2)
                .rotationEffect(.degrees(angle))
        }
        .frame(width: diameter, height: diameter)
        .onAppear {
            // Driven off a state change rather than `repeatForever` on a
            // constant: an animation attached to a value that never changes is
            // not restarted when the view reappears, and this view is torn down
            // and rebuilt every transaction.
            withAnimation(.linear(duration: 2.5).repeatForever(autoreverses: false)) {
                angle = 360
            }
        }
        .onDisappear { angle = 0 }
    }
}
