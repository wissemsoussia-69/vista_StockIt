import AVFoundation
import PhotosUI
import SwiftUI
import UIKit

/// Caméra (équivalent CameraX) : aperçu, capture photo, torche, zoom,
/// et analyse vidéo optionnelle (scan code-barres en rafale).
final class CameraService: NSObject, ObservableObject {
    @Published var authorized: Bool?
    @Published var torchOn = false
    @Published var zoom: CGFloat = 1.0
    @Published var hasTorch = false

    let session = AVCaptureSession()
    private let photoOutput = AVCapturePhotoOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    private var device: AVCaptureDevice?
    private let queue = DispatchQueue(label: "stockit.camera")
    private var photoContinuation: CheckedContinuation<Data, Error>?
    private var configured = false

    /// Rappel pour chaque code-barres détecté (mode rafale).
    var onBarcodes: (([(String, CGRect)]) -> Void)?
    var quality: AVCapturePhotoOutput.QualityPrioritization = .speed

    func start(barcodes: Bool = false) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: authorized = true; configure(barcodes: barcodes)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    self.authorized = ok
                    if ok { self.configure(barcodes: barcodes) }
                }
            }
        default: authorized = false
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    private func configure(barcodes: Bool) {
        queue.async {
            if !self.configured {
                self.session.beginConfiguration()
                self.session.sessionPreset = .photo
                guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                      let input = try? AVCaptureDeviceInput(device: dev),
                      self.session.canAddInput(input) else {
                    self.session.commitConfiguration(); return
                }
                self.device = dev
                self.session.addInput(input)
                if self.session.canAddOutput(self.photoOutput) {
                    self.session.addOutput(self.photoOutput)
                    self.photoOutput.maxPhotoQualityPrioritization = .quality
                }
                if barcodes, self.session.canAddOutput(self.metadataOutput) {
                    self.session.addOutput(self.metadataOutput)
                    self.metadataOutput.setMetadataObjectsDelegate(self, queue: .main)
                    self.metadataOutput.metadataObjectTypes = self.metadataOutput.availableMetadataObjectTypes
                }
                self.session.commitConfiguration()
                self.configured = true
                DispatchQueue.main.async { self.hasTorch = dev.hasTorch }
            }
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    func capturePhoto() async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            queue.async {
                guard self.session.isRunning else {
                    cont.resume(throwing: NSError(domain: "StockIT", code: 2,
                                                  userInfo: [NSLocalizedDescriptionKey: L("Caméra indisponible")]))
                    return
                }
                self.photoContinuation = cont
                let s = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                let maxQ = self.photoOutput.maxPhotoQualityPrioritization
                s.photoQualityPrioritization = self.quality.rawValue <= maxQ.rawValue ? self.quality : maxQ
                self.photoOutput.capturePhoto(with: s, delegate: self)
            }
        }
    }

    func toggleTorch() {
        guard let d = device, d.hasTorch else { return }
        try? d.lockForConfiguration()
        d.torchMode = d.torchMode == .on ? .off : .on
        d.unlockForConfiguration()
        torchOn = d.torchMode == .on
    }

    func setZoom(_ factor: CGFloat) {
        guard let d = device else { return }
        let z = max(d.minAvailableVideoZoomFactor, min(factor, min(d.maxAvailableVideoZoomFactor, 10)))
        try? d.lockForConfiguration()
        d.videoZoomFactor = z
        d.unlockForConfiguration()
        zoom = z
    }
}

extension CameraService: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let cont = photoContinuation
        photoContinuation = nil
        if let error { cont?.resume(throwing: error); return }
        guard let data = photo.fileDataRepresentation() else {
            cont?.resume(throwing: NSError(domain: "StockIT", code: 3, userInfo: [NSLocalizedDescriptionKey: L("Photo vide")]))
            return
        }
        cont?.resume(returning: data)
    }
}

extension CameraService: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        let codes = objects.compactMap { o -> (String, CGRect)? in
            guard let m = o as? AVMetadataMachineReadableCodeObject, let v = m.stringValue else { return nil }
            return (v, m.bounds)
        }
        if !codes.isEmpty { onBarcodes?(codes) }
    }
}

/// Aperçu caméra SwiftUI (pinch-to-zoom inclus).
struct CameraPreview: UIViewRepresentable {
    @ObservedObject var camera: CameraService

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.previewLayer.session = camera.session
        v.previewLayer.videoGravity = .resizeAspectFill
        v.addGestureRecognizer(UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pinch(_:))))
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(camera) }

    final class Coordinator: NSObject {
        let camera: CameraService
        private var base: CGFloat = 1
        init(_ c: CameraService) { camera = c }
        @objc func pinch(_ g: UIPinchGestureRecognizer) {
            if g.state == .began { base = camera.zoom }
            camera.setZoom(base * g.scale)
        }
    }
}

/// Écran caméra commun : aperçu + statut + boutons (Capturer / Annuler).
struct CameraScreen<Overlay: View>: View {
    @ObservedObject var camera: CameraService
    let status: String
    let busy: Bool
    let captureTitle: String
    let cancelTitle: String
    let onCapture: () -> Void
    let onCancel: () -> Void
    /// Photo choisie dans la galerie (permet aussi de tester sur le simulateur).
    var onPickPhoto: ((Data) -> Void)? = nil
    @ViewBuilder var overlay: Overlay
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if camera.authorized == true {
                CameraPreview(camera: camera).ignoresSafeArea()
            } else if camera.authorized == false {
                Text("Permission caméra refusée").foregroundColor(.white)
            }
            overlay
            VStack {
                HStack {
                    Spacer()
                    if camera.hasTorch {
                        Button { camera.toggleTorch() } label: {
                            Image(systemName: camera.torchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                                .padding(12)
                                .background(camera.torchOn ? Color.vistaAmber : Color.black.opacity(0.5), in: Circle())
                                .foregroundColor(.white)
                        }
                    }
                }
                .padding()
                if camera.zoom > 1.01 {
                    Text(String(format: "%.1f×", camera.zoom))
                        .font(.caption.bold()).padding(6)
                        .background(.ultraThinMaterial, in: Capsule())
                }
                Spacer()
                VStack(spacing: 12) {
                    HStack(spacing: 8) {
                        if busy { ProgressView() }
                        Text(status).font(.subheadline).multilineTextAlignment(.center)
                    }
                    HStack(spacing: 12) {
                        Button(cancelTitle, action: onCancel)
                            .buttonStyle(PrimaryButtonStyle(color: .vistaNavyMuted))
                        Button(captureTitle, action: onCapture)
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(busy || camera.authorized != true)
                    }
                    #if DEBUG
                    // Tests automatiques uniquement : photo injectée via l'environnement STOCKIT_TEST_PHOTO.
                    if let path = ProcessInfo.processInfo.environment["STOCKIT_TEST_PHOTO"], onPickPhoto != nil {
                        Button("Photo de test") {
                            if let d = FileManager.default.contents(atPath: path) { onPickPhoto?(d) }
                        }
                        .accessibilityIdentifier("testPhoto")
                    }
                    #endif
                    if onPickPhoto != nil {
                        PhotosPicker(selection: $pickerItem, matching: .images) {
                            Label("Choisir une photo", systemImage: "photo.on.rectangle")
                                .font(.subheadline.weight(.semibold))
                        }
                        .disabled(busy)
                        .accessibilityIdentifier("pickPhoto")
                    }
                }
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
                .padding()
            }
        }
        .onChange(of: pickerItem) { item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let img = UIImage(data: data)?.normalized(),
                   let jpeg = img.jpegData(compressionQuality: 0.9) {
                    onPickPhoto?(jpeg)
                }
                pickerItem = nil
            }
        }
    }
}
