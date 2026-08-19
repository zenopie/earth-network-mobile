import CoreImage.CIFilterBuiltins
import EarthCore
import SwiftUI

struct ReceiveSheet: View {
    @Environment(\.earth) private var theme
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationStack {
            VStack(spacing: theme.space.x20) {
                if let qr = QRCode.image(for: model.address) {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 260)
                        .padding(theme.space.x16)
                        .background(Palette.Base.bone, in: .rect(cornerRadius: theme.space.radiusLg))
                }

                Text(model.address)
                    .font(EarthType.mono)
                    .foregroundStyle(theme.colors.textSecondary)
                    .multilineTextAlignment(.center)
                    .textSelection(.enabled)
                    .padding(.horizontal, theme.space.gutter)

                EarthButton(title: copied ? "Copied" : "Copy address", role: .secondary) {
                    UIPasteboard.general.string = model.address
                    copied = true
                }
                .padding(.horizontal, theme.space.gutter)

                Spacer()
            }
            .padding(.top, theme.space.x24)
            .navigationTitle("Receive")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .earthBackground()
        }
    }
}

/// A QR of the address.
///
/// Generated rather than fetched — an address is the one thing in a wallet that
/// must never make a network round trip to be displayed.
enum QRCode {
    static func image(for text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        // High correction: this gets scanned off a screen at an angle, in a
        // café, by a camera that is not holding still.
        filter.correctionLevel = "H"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
