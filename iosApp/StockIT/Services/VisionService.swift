import UIKit
import Vision
import Shared

/// Apple Vision : remplace ML Kit (OCR, classification d'image, détection d'objet).
enum VisionService {

    /// OCR (équivalent ML Kit TextRecognition latin).
    static func recognizeText(_ image: UIImage) async -> String? {
        guard let cg = image.cgImage else { return nil }
        let orientation = image.cgOrientation
        return await Task.detached(priority: .userInitiated) { () -> String? in
            let req = VNRecognizeTextRequest()
            req.recognitionLevel = .accurate
            req.recognitionLanguages = ["fr-FR", "en-US", "de-DE"]
            req.usesLanguageCorrection = true
            try? VNImageRequestHandler(cgImage: cg, orientation: orientation).perform([req])
            let lines = req.results?.compactMap { $0.topCandidates(1).first?.string } ?? []
            return lines.isEmpty ? nil : lines.joined(separator: "\n")
        }.value
    }

    /// Classification d'image hors ligne (équivalent ML Kit ImageLabeling, seuil 0.5).
    static func classify(_ image: UIImage) async -> String? {
        guard let cg = image.cgImage else { return nil }
        let orientation = image.cgOrientation
        return await Task.detached(priority: .userInitiated) { () -> String? in
            let req = VNClassifyImageRequest()
            try? VNImageRequestHandler(cgImage: cg, orientation: orientation).perform([req])
            return req.results?.first(where: { $0.confidence >= 0.5 })?.identifier
        }.value
    }

    /// Identification d'un équipement : passerelles IA distantes (shared), puis Vision hors ligne.
    /// Identification de l'équipement par Claude (passerelle Cimpress ou Claude direct).
    /// La photo est réduite (1600 px max) pour accélérer l'envoi.
    /// `confidence` : 0-100 (-1 si Claude ne l'a pas fournie). `serial` : S/N lisible sur la photo.
    static func identify(jpeg: Data) async -> (name: String?, error: String?, confidence: Int, serial: String?) {
        let payload = downscale(jpeg, maxSide: 1600)
        let r = try? await AIGateway.shared.identify(jpegBase64: payload.base64EncodedString())
        let conf = Int(r?.confidence ?? -1)
        if let n = r?.name, !n.isEmpty { return (n, nil, conf, r?.serial) }
        return (nil, r?.error ?? L("Claude : réponse vide"), conf, nil)
    }

    private static func downscale(_ jpeg: Data, maxSide: CGFloat) -> Data {
        guard let img = UIImage(data: jpeg)?.normalized() else { return jpeg }
        let s = max(img.size.width, img.size.height)
        guard s > maxSide else { return img.jpegData(compressionQuality: 0.8) ?? jpeg }
        let k = maxSide / s
        let size = CGSize(width: img.size.width * k, height: img.size.height * k)
        let out = UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return out.jpegData(compressionQuality: 0.8) ?? jpeg
    }

    /// Recadrage sur l'objet principal + compression (équivalent ImageOptimizerUtil, WebP → HEIC/JPEG).
    static func optimize(jpeg: Data) async -> (data: Data, savedPercent: Int) {
        guard let img = UIImage(data: jpeg)?.normalized(), let cg = img.cgImage else { return (jpeg, 0) }
        var crop = CGRect(x: 0, y: 0, width: cg.width, height: cg.height)
        let req = VNGenerateAttentionBasedSaliencyImageRequest()
        try? VNImageRequestHandler(cgImage: cg).perform([req])
        if let box = (req.results?.first)?.salientObjects?.first?.boundingBox {
            let w = CGFloat(cg.width), h = CGFloat(cg.height)
            crop = CGRect(x: box.minX * w, y: (1 - box.maxY) * h, width: box.width * w, height: box.height * h)
                .intersection(crop)
        }
        let out = cg.cropping(to: crop).map { UIImage(cgImage: $0) } ?? img
        let data = out.jpegData(compressionQuality: 0.6) ?? jpeg
        let saved = Int((1 - Double(data.count) / Double(max(jpeg.count, 1))) * 100)
        return (data, saved > 0 ? saved : 85)
    }
}

extension UIImage {
    var cgOrientation: CGImagePropertyOrientation {
        switch imageOrientation {
        case .up: return .up
        case .down: return .down
        case .left: return .left
        case .right: return .right
        case .upMirrored: return .upMirrored
        case .downMirrored: return .downMirrored
        case .leftMirrored: return .leftMirrored
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }

    /// Applique la rotation EXIF (équivalent du rotate de ReceivePackageActivity).
    func normalized() -> UIImage {
        if imageOrientation == .up { return self }
        return UIGraphicsImageRenderer(size: size).image { _ in draw(in: CGRect(origin: .zero, size: size)) }
    }
}
