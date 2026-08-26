import SwiftUI

/// One option in an allocation stream, and the share given to it.
struct AllocationSlice: Identifiable, Equatable {
    let name: String
    let percent: Int

    var id: String { name }
}

/// A share of a whole, as a ring.
///
/// A ring rather than a filled pie: a reader comparing two of these compares
/// arc lengths either way, and the centre of a pie carries no information while
/// being the part hardest to judge by eye.
///
/// The hole used to state the allocated total. It was always "100%" for any
/// valid allocation — the slices are a split of the whole, so their sum is a
/// restatement of the thing the ring already draws — and it showed the
/// committed split rather than the one being dragged in the editor, so it read
/// as a figure that would not update.
///
/// Slices take `Palette.Series`, a fixed categorical order, because hue here is
/// doing identity work — which slice is which. They used to be one accent hue
/// stepped 16% in opacity per slice, which is a sequential ramp asked to encode
/// identity: adjacent slices differed by almost nothing and the fourth onward
/// were mud. The one-accent rule still governs the rest of the app; a chart's
/// interior is where hue has to mean something.
///
/// Arcs are separated by a hairline gap of surface. It reads as division
/// without a second colour, and it is also what lets the palette's tighter
/// pairs stand up under simulated colourblindness.
struct PieChart: View {
    @Environment(\.earth) private var theme
    let slices: [AllocationSlice]
    /// Written in the hole, and only worth it when there is nothing to draw: a
    /// stream nobody has voted in is an empty circle, which is otherwise
    /// indistinguishable from one that failed to load.
    var centreLabel: String?

    private static let size: CGFloat = 180
    private static let stroke: CGFloat = 34
    /// Surface showing between arcs. See the doc comment.
    private static let gapPoints: CGFloat = 2

    /// Sweeps between splits, so switching Actual to Preferred reads as the
    /// same chart changing rather than two charts.
    ///
    /// Starts at 1, not 0. It used to sweep in on first appear too, driven from
    /// `onAppear` — but a `withAnimation` issued in the frame a sheet is
    /// presenting gets dropped, which left `progress` at zero and the ring
    /// drawn with no sweep at all. An invisible chart is a worse trade than a
    /// missing flourish, and the animation's stated job — making the two lenses
    /// read as one chart — is untouched.
    @State private var progress: CGFloat = 1

    var body: some View {
        ZStack {
            Canvas { context, size in
                let total = max(slices.reduce(0) { $0 + $1.percent }, 1)
                let inset = Self.stroke / 2
                let box = CGRect(x: inset, y: inset,
                                 width: size.width - Self.stroke,
                                 height: size.height - Self.stroke)
                // Twelve o'clock, where a reader starts.
                var start = Angle.degrees(-90)
                let radius = box.width / 2
                // Two points of arc, as an angle. A single slice gets none —
                // there is nothing to divide it from, and a gap would read as a
                // ring that failed to close.
                let gap = slices.count > 1
                    ? Angle.degrees(Double(Self.gapPoints) / (2 * .pi * radius) * 360)
                    : Angle.degrees(0)

                for (index, slice) in slices.enumerated() {
                    let sweep = Angle.degrees(360 * Double(slice.percent) / Double(total) * progress)
                    // A slice thinner than the gap keeps its colour rather than
                    // vanishing: an option someone allocated to must still be
                    // findable next to its legend row.
                    let inner = sweep.degrees > gap.degrees ? sweep - gap : sweep
                    var arc = Path()
                    arc.addArc(
                        center: CGPoint(x: box.midX, y: box.midY),
                        radius: radius,
                        startAngle: start,
                        endAngle: start + inner,
                        clockwise: false
                    )
                    context.stroke(
                        arc,
                        with: .color(Palette.Series.slot(index)),
                        style: StrokeStyle(lineWidth: Self.stroke, lineCap: .butt)
                    )
                    start += sweep
                }
            }
            .frame(width: Self.size, height: Self.size)

            if let centreLabel {
                Text(centreLabel)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
            }
        }
        .frame(width: Self.size, height: Self.size)
        .onChange(of: slices) { _, _ in
            progress = 0
            withAnimation(.easeInOut(duration: 0.5)) { progress = 1 }
        }
    }
}

/// The chart's key.
///
/// Separate from the chart so a caller can put it beside or beneath depending
/// on width. Dots reuse the chart's stepped opacity, which is the only thing
/// tying a row to its arc.
struct PieLegend: View {
    @Environment(\.earth) private var theme
    let slices: [AllocationSlice]

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(slices.enumerated()), id: \.element.id) { index, slice in
                HStack(spacing: theme.space.x4) {
                    Circle()
                        .fill(Palette.Series.slot(index))
                        .frame(width: 10, height: 10)
                    Text(slice.name)
                        .font(EarthType.bodySmall)
                        .foregroundStyle(theme.colors.textPrimary)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("\(slice.percent)%")
                        .font(EarthType.bodySmall).fontWeight(.semibold)
                        .foregroundStyle(theme.colors.textSecondary)
                }
                .padding(.vertical, theme.space.x4)
            }
            if slices.isEmpty {
                Spacer().frame(height: theme.space.x8)
                Text("Nothing allocated.")
                    .font(EarthType.bodySmall)
                    .foregroundStyle(theme.colors.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }
}
