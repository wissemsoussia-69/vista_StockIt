import UIKit
import UserNotifications
import WidgetKit
import Shared

/// Notifications locales (NotificationHelper Android, canal "stock_alerts").
enum NotificationService {
    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func show(_ title: String, _ body: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = .default
        c.threadIdentifier = "stock_alerts"
        let req = UNNotificationRequest(identifier: UUID().uuidString, content: c,
                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        UNUserNotificationCenter.current().add(req)
    }
}

/// Retours haptiques / vibrations (ScanAssetActivity.vibrateSuccess).
enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

/// Rapport mensuel PDF A4 (util/PdfReportGenerator.java).
enum PdfReportService {
    static func monthlyReport(summary: String) -> URL? {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("Rapport_Stock_\(nowMillis()).pdf")
        let df = DateFormatter(); df.dateFormat = "dd/MM/yyyy"
        do {
            try UIGraphicsPDFRenderer(bounds: page).writePDF(to: url) { ctx in
                ctx.beginPage()
                (L("StockIT - Rapport Mensuel d'Inventaire") as NSString).draw(at: CGPoint(x: 50, y: 40), withAttributes: [
                    .font: UIFont.boldSystemFont(ofSize: 22), .foregroundColor: UIColor.systemBlue])
                (L("Généré le : ") + df.string(from: Date()) as NSString).draw(at: CGPoint(x: 50, y: 75), withAttributes: [
                    .font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.gray])
                let style = NSMutableParagraphStyle(); style.lineSpacing = 6
                (summary as NSString).draw(in: CGRect(x: 50, y: 120, width: 495, height: 660), withAttributes: [
                    .font: UIFont.systemFont(ofSize: 14), .foregroundColor: UIColor.black, .paragraphStyle: style])
            }
            return url
        } catch { return nil }
    }
}

/// Données partagées avec le widget (App Group).
enum WidgetBridge {
    static let suite = "group.com.ETX.stockit"

    static func update(repo: StockItRepository) async {
        guard let d = try? await repo.getReportData() else { return }
        let ud = UserDefaults(suiteName: suite)
        ud?.set(Int(d.totalQuantity), forKey: "widget_total")
        ud?.set(Int(d.lowStockCount), forKey: "widget_low")
        ud?.set(Int(d.outOfStockCount), forKey: "widget_out")
        WidgetCenter.shared.reloadAllTimelines()
    }
}
