import SwiftUI

/// One option in an allocation stream, and the share given to it.
struct AllocationSlice: Identifiable, Equatable {
    let name: String
    let percent: Int

    var id: String { name }
}

/// A share of a whole, as a ring.
///
/// A ring rather than a filled pie: the hole gives the total somewhere to live,
/// and a reader comparing two of these compares arc lengths either way — the
/// centre of a pie carries no information and is the part hardest to judge by
/// eye.
///
/// Slices are one hue at stepped opacity rather than a palette. The chart is
/// labelled beside itself, so a second colour per slice would encode nothing
/// the legend does not, and the screen keeps one accent.
struct PieChart: View {
    @Environment(\.earth) private var theme
    let slices: [AllocationSlice]
    /// Written in the hole. The total allocated, or "none".
    var centreLabel: String?

    private static let size: CGFloat = 180
    private static let stroke: CGFloat = 34

    /// Sweeps in rather than snapping, so switching between Actual and
    /// Preferred reads as the same chart changing rather than two charts.
    @State private var progress: CGFloat = 0

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

                for (index, slice) in slices.enumerated() {
                    let sweep = Angle.degrees(360 * Double(slice.percent) / Double(total) * progress)
                    var arc = Path()
                    arc.addArc(
                        center: CGPoint(x: box.midX, y: box.midY),
                        radius: box.width / 2,
                        startAngle: start,
                        endAngle: start + sweep,
                        clockwise: false
                    )
                    context.stroke(
                        arc,
                        with: .color(theme.colors.accentInk.stepped(index)),
                        style: StrokeStyle(lineWidth: Self.stroke)
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
        .onAppear { withAnimation(.easeInOut(duration: 0.5)) { progress = 1 } }
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
                        .fill(theme.colors.accentInk.stepped(index))
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

extension Color {
    /// Steps down the accent so adjacent slices separate without a second hue.
    func stepped(_ index: Int) -> Color {
        opacity(max(1 - Double(index) * 0.16, 0.28))
    }
}
