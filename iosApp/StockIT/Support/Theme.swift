import SwiftUI
import Shared

// MARK: - Couleurs Vista (res/values/colors.xml)

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }

    init?(hexString: String?) {
        guard var s = hexString?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        if s.hasPrefix("#") { s.removeFirst() }
        guard let v = UInt32(s, radix: 16) else { return nil }
        self.init(hex: v)
    }

    static let vistaBlue = Color(hex: 0x0F53FA)
    static let vistaBlueDeep = Color(hex: 0x0033A0)
    static let vistaBlueSoft = Color(hex: 0x3D6BFF)
    static let vistaNavy = Color(hex: 0x0B1F44)
    static let vistaNavyMuted = Color(hex: 0x4A5A78)
    static let vistaNavyFaint = Color(hex: 0x8A96AC)
    static let vistaBg = Color(hex: 0xF5F7FB)
    static let vistaMint = Color(hex: 0x3DDC97)
    static let vistaTurquoise = Color(hex: 0x1DE9B6)
    static let vistaMintSoft = Color(hex: 0xE7FAF1)
    static let vistaCoral = Color(hex: 0xFF6B4A)
    static let vistaAmber = Color(hex: 0xFFB020)
    static let vistaUrgencySoft = Color(hex: 0xFFF0EA)
}

struct VistaCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
            .shadow(color: Color.vistaBlue.opacity(0.08), radius: 8, y: 3)
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var color: Color = .vistaBlue
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(color.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 14))
    }
}

// MARK: - Interop Kotlin

extension Product: Identifiable {}
extension User: Identifiable {}
extension Supplier: Identifiable {}
extension PurchaseOrder: Identifiable {}
extension ShippingOrder: Identifiable {}
extension Claim: Identifiable {}
extension ProductCategory: Identifiable {}
extension Quest: Identifiable {}
extension AuditLog: Identifiable {}
extension StockMovement: Identifiable {}

extension Double {
    var money: String { StockItRepositoryKt.formatMoney(v: self) + " €" }
}

func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

func formatTimestamp(_ ms: Int64) -> String {
    let f = DateFormatter()
    f.dateFormat = "dd/MM/yyyy HH:mm"
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

extension String {
    var nilIfEmpty: String? { let t = trimmingCharacters(in: .whitespacesAndNewlines); return t.isEmpty ? nil : t }
    func preview(_ max: Int) -> String { count > max ? String(prefix(max)) + "…" : self }
}

/// Lance une tâche réseau « fire-and-forget » (Slack, e-mail n8n…) sans bloquer l'UI.
enum Bg {
    static func run(_ op: @escaping () async throws -> Void) {
        Task.detached(priority: .utility) { try? await op() }
    }

    static func slack(_ msg: String) {
        run { _ = try await SlackNotifier.shared.send(message: msg) }
    }

    /// E-mail via le workflow n8n (destinataire par défaut du projet).
    static func email(_ subject: String, _ body: String) {
        run {
            _ = try await StockItReporter.shared.sendEvent(
                subjectSuffix: subject, message: body,
                recipientEmail: StockItConfig.shared.defaultRecipientEmail)
        }
    }
}

/// Alerte générique (titre + message).
struct InfoAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

/// Ligne vide / état vide.
struct EmptyStateView: View {
    let icon: String
    let title: String
    let subtitle: String
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 44)).foregroundColor(.vistaNavyFaint)
            // Traduction via L() : la clé (texte FR) est traduite, un texte déjà traduit reste inchangé.
            Text(verbatim: L(title)).font(.headline)
            Text(verbatim: L(subtitle)).font(.subheadline).foregroundColor(.secondary).multilineTextAlignment(.center)
        }
        .padding(40)
        .frame(maxWidth: .infinity)
    }
}

/// Badge de pastille.
struct Pill: View {
    let text: String
    let color: Color
    var foreground: Color = .white
    var body: some View {
        Text(verbatim: L(text))
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(color, in: Capsule())
            .foregroundColor(foreground)
    }
}

/// Badge « Confiance IA » : vert ≥ 85, orange 60-84, rouge < 60 (confirmation obligatoire).
struct ConfidenceBadge: View {
    let value: Int
    var body: some View {
        if value >= 0 {
            let level = Confidence.shared.level(c: Int32(value))
            Pill(text: L("Confiance IA %@ %%", String(describing: value)),
                 color: level == "high" ? .vistaMint : level == "medium" ? .vistaAmber : .vistaCoral,
                 foreground: level == "high" ? .vistaNavy : .white)
                .accessibilityIdentifier("confidenceBadge")
        }
    }
}
