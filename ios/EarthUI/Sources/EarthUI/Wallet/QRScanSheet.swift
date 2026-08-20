import AVFoundation
import EarthCore
import SwiftUI

/// Reads an address off a QR code.
///
/// `AVCaptureMetadataOutput` rather than Vision: the hardware decodes QR in
/// the capture pipeline, so this costs nothing per frame and fires the moment
/// the code is in shot. Vision earns its place on the MRZ, where the thing
/// being read is printed text; here it would be slower for no gain.
@MainActor
final class QRScanner: NSObject, ObservableObject {

    enum Access { case unknown, granted, denied }

    @Published private(set) var access: Access = .unknown
    /// Latches, because the session keeps running for a frame or two after a
    /// code is seen and the same value would otherwise arrive several times.
    @Published private(set) var found: String?

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "network.erth.wallet.qr")
    private var configured = false

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            access = .granted
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                Task { @MainActor in
                    self?.access = granted ? .granted : .denied
                    if granted { self?.configure() }
                }
            }
        default:
            access = .denied
        }
    }

    func stop() {
        queue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configure() {
        guard !configured else {
            queue.async { [session] in if !session.isRunning { session.startRunning() } }
            return
        }
        configured = true

        session.beginConfiguration()
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            // Set after adding, or the type is not yet available to request.
            output.metadataObjectTypes = [.qr]
        }
        session.commitConfiguration()

        queue.async { [session] in session.startRunning() }
    }
}

extension QRScanner: AVCaptureMetadataOutputObjectsDelegate {
    nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput objects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let code = objects.first as? AVMetadataMachineReadableCodeObject,
              let value = code.stringValue
        else { return }
        Task { @MainActor in
            guard found == nil else { return }
            found = value
            stop()
        }
    }
}

/// Pull an earth address out of whatever was scanned.
///
/// A QR may carry a bare address, a URI, or an address with a memo hung off
/// it. This looks for a candidate at each `earth1` and keeps the first whose
/// bech32 checksum holds — stronger than matching a shape, because a truncated
/// or mistyped address matches the shape and fails the checksum.
///
/// The raw text is returned when nothing validates, so a near miss reaches the
/// field where the user can see what was read instead of being discarded.
func earthAddress(in scanned: String) -> String {
    let trimmed = scanned.trimmingCharacters(in: .whitespacesAndNewlines)
    let characters = Array(trimmed.lowercased())
    let prefix = Array("earth1")

    for start in characters.indices where Array(characters[start...].prefix(6)) == prefix {
        var end = start
        while end < characters.count, characters[end].isNumber || characters[end].isLetter {
            end += 1
        }
        // Trim from the right: trailing text run together with the address
        // would otherwise swallow it whole.
        var candidateEnd = end
        while candidateEnd > start {
            let candidate = String(characters[start ..< candidateEnd])
            if EarthKey.isValidAddress(candidate) { return candidate }
            candidateEnd -= 1
        }
    }
    return trimmed
}

struct QRScanSheet: View {
    @Environment(\.earth) private var theme
    @Environment(\.dismiss) private var dismiss
    @StateObject private var scanner = QRScanner()

    let onScanned: (String) -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if scanner.access == .granted {
                CameraPreview(session: scanner.session, angle: 90)
                    .ignoresSafeArea()
            }

            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(.white, lineWidth: 2)
                .frame(width: 240, height: 240)

            VStack {
                Text(instruction)
                    .font(EarthType.bodySmall)
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(.black.opacity(0.55))
                Spacer()
                HStack {
                    Button("Cancel") { dismiss() }
                        .font(EarthType.body)
                        .foregroundStyle(.white)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .background(.black.opacity(0.55))
            }
        }
        .task { scanner.start() }
        .onDisappear { scanner.stop() }
        .onChange(of: scanner.found) { _, value in
            guard let value else { return }
            onScanned(earthAddress(in: value))
            dismiss()
        }
    }

    private var instruction: String {
        switch scanner.access {
        case .granted: "Point the camera at the sender's QR code."
        case .denied: "Camera access is off. Turn it on in Settings, or paste the address instead."
        case .unknown: "Camera access is needed to read a QR code."
        }
    }
}
