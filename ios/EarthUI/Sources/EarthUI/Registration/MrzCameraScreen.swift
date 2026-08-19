import EarthCore
import SwiftUI

/// Point the camera at the two lines at the bottom of the passport.
///
/// Full-bleed camera with everything drawn over it. Overlaying rather than
/// stacking keeps the words next to the box they describe, rather than under a
/// viewfinder the reader has stopped looking at.
///
/// The screen turns landscape, as Android's does. In portrait the frame is
/// barely wider than the passport is tall, so two 44-character lines land too
/// small for the recognizer to hold; the long way round they fill it. This is
/// the only screen in the app that turns.
struct MrzCameraScreen: View {
    @Environment(\.earth) private var theme
    @StateObject private var scanner = MRZScanner()

    let onDetected: (MRZ.Key) -> Void
    let onManualEntry: () -> Void

    /// The machine-readable zone's proportions.
    ///
    /// Two 44-character lines in OCR-B across a 125mm page: about seven times
    /// as wide as it is tall. The guide is drawn at that ratio, so what the
    /// reader lines up against is the shape of the thing they are pointing at.
    private let aspect: CGFloat = 7

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if scanner.access == .granted {
                CameraPreview(session: scanner.session)
                    .ignoresSafeArea()
            }

            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(.white, lineWidth: 2)
                .aspectRatio(aspect, contentMode: .fit)
                .padding(.horizontal, 16)

            VStack {
                // Scrims top and bottom. Text over a live camera is unreadable
                // against a pale passport page and fine against a dark desk; a
                // scrim makes it legible against both without dimming the part
                // being framed.
                Text(instruction)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(.black.opacity(0.55))

                Spacer()

                HStack {
                    Button("Enter manually", action: onManualEntry)
                        .font(EarthType.body)
                        .foregroundStyle(.white)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .background(.black.opacity(0.55))
            }
        }
        .orientationLock(.landscape, turningTo: .landscapeRight)
        .task { scanner.start() }
        .onDisappear { scanner.stop() }
        .onChange(of: scanner.found) { _, key in
            if let key { onDetected(key) }
        }
    }

    private var instruction: String {
        switch scanner.access {
        case .granted:
            "Lay the passport flat and fill the box with the two rows of letters and chevrons."
        case .denied:
            "Camera access is off. Turn it on in Settings, or enter the three fields by hand."
        case .unknown:
            "Camera access is needed to read the passport's printed lines. Nothing is recorded."
        }
    }
}
