import AVFoundation
import EarthCore
import SwiftUI
import Vision

/// Reads the two printed lines at the bottom of a passport.
///
/// The MRZ is only read to unlock the chip. Nothing scanned here is stored or
/// sent: the passport's own data comes off the chip a step later, and this text
/// is discarded as soon as the chip accepts the key.
///
/// Vision rather than a model download — the recognizer is in the OS, works
/// offline, and a passport page is exactly the high-contrast printed text it is
/// built for.
@MainActor
final class MRZScanner: NSObject, ObservableObject {

    enum Access { case unknown, granted, denied }

    @Published private(set) var access: Access = .unknown
    /// Set once, when a zone parses. The session keeps running for a frame or
    /// two after, so this latches rather than firing repeatedly.
    @Published private(set) var found: MRZ.Key?

    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "network.erth.wallet.mrz")
    private var configured = false
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var rotationObserver: NSKeyValueObservation?

    /// The angle the preview layer needs.
    ///
    /// Published separately because the preview layer is *not* one of the
    /// session's outputs — rotating those leaves the picture on screen sideways
    /// while the frames Vision sees are upright, which is exactly as confusing
    /// as it sounds.
    @Published private(set) var previewAngle: CGFloat = 0

    private func applyRotation() {
        guard let rotation else { return }

        let capture = rotation.videoRotationAngleForHorizonLevelCapture
        for output in session.outputs {
            for connection in output.connections
            where connection.isVideoRotationAngleSupported(capture) {
                connection.videoRotationAngle = capture
            }
        }
        previewAngle = rotation.videoRotationAngleForHorizonLevelPreview
    }

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
        // The zone is small type read at arm's length, so the frame wants
        // every pixel it can get; the default preset loses the thin strokes
        // that tell 8 from B.
        session.sessionPreset = .hd1920x1080

        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            session.commitConfiguration()
            return
        }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()

        // Let the capture pipeline do the rotating, so frames arrive upright
        // whichever way the phone is held and Vision can be told `.up`.
        //
        // Guessing the orientation from the interface instead is what breaks
        // when the user turns from one landscape to the other: the picture
        // stays upright on screen while the buffer flips, and the recognizer
        // silently reads nothing.
        let coordinator = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)
        self.rotation = coordinator
        applyRotation()
        rotationObserver = coordinator.observe(
            \.videoRotationAngleForHorizonLevelPreview,
            options: [.new]
        ) { [weak self] _, _ in
            Task { @MainActor in self?.applyRotation() }
        }

        queue.async { [session] in session.startRunning() }
    }
}

extension MRZScanner: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        let request = VNRecognizeTextRequest { [weak self] request, _ in
            let lines = (request.results as? [VNRecognizedTextObservation] ?? [])
                .compactMap { $0.topCandidates(1).first?.string }
            guard let key = MRZScanner.parse(lines: lines) else { return }
            Task { @MainActor in
                guard self?.found == nil else { return }
                self?.found = key
                self?.stop()
            }
        }
        request.recognitionLevel = .accurate
        // Off, and this matters more than usual: the dictionary "corrects"
        // chevron runs and OCR-B digits into words, which is precisely the
        // text being read.
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]

        // Upright already — the capture connection is rotated for us.
        try? VNImageRequestHandler(cvPixelBuffer: buffer, orientation: .up, options: [:])
            .perform([request])
    }

    /// Pull the three chip-access fields out of recognised lines.
    ///
    /// Deliberately tolerant, as the Android side is: OCR mangles this typeface
    /// reliably, so this looks for any adjacent pair where the first begins
    /// "P<" and the second is long enough, rather than insisting the whole zone
    /// parsed cleanly. The chip rejects a wrong key a moment later, which is a
    /// better check than anything done here.
    static func parse(lines: [String]) -> MRZ.Key? {
        let cleaned = lines.map {
            $0.replacingOccurrences(of: " ", with: "").uppercased()
        }
        for index in cleaned.indices.dropLast() {
            let first = cleaned[index]
            let second = cleaned[index + 1]
            guard first.hasPrefix("P<"), second.count >= 36 else { continue }

            let characters = Array(second)
            let key = MRZ.Key(
                documentNumber: String(characters[0 ..< 9])
                    .replacingOccurrences(of: "<", with: " ")
                    .trimmingCharacters(in: .whitespaces),
                dateOfBirth: String(characters[13 ..< 19]),
                dateOfExpiry: String(characters[21 ..< 27])
            )
            if key.isComplete { return key }
        }
        return nil
    }
}

/// The live preview. There is no SwiftUI camera surface, so the layer that
/// exists is wrapped — which is the intended way round rather than a
/// compromise.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    /// Applied to the preview layer's own connection, which the session's
    /// outputs do not cover.
    let angle: CGFloat

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.layer.session = session
        view.layer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        guard let connection = view.layer.connection,
              connection.isVideoRotationAngleSupported(angle)
        else { return }
        connection.videoRotationAngle = angle
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        override var layer: AVCaptureVideoPreviewLayer { super.layer as! AVCaptureVideoPreviewLayer }
    }
}
