import SwiftUI
import Shared

/// État global de l'app (équivalent Splash/Login/Main + session Android).
@MainActor
final class AppState: ObservableObject {
    enum Phase { case splash, login, main }

    @Published var phase: Phase = .splash
    @Published var user: User?
    @Published var toast: Toast?
    /// true juste après un changement de langue : l'onglet « Plus » rouvre Paramètres.
    @Published var reopenSettings = false

    /// Incrémenté après chaque mutation pour forcer le rechargement des écrans.
    @Published var dataVersion = 0

    var repo: StockItRepository { StockIt.shared.repository }
    var session: SessionStore { StockIt.shared.session }

    var isAdmin: Bool { user?.isAdmin ?? false }
    var canEditStock: Bool { user?.canEditStock ?? false }

    /// Vrai pendant l'ouverture de la page Okta (l'app passe brièvement en arrière-plan).
    var signingIn = false

    /// Splash : sécurité — aucune session n'est conservée d'un lancement à l'autre.
    /// L'employé doit toujours se reconnecter avec Okta.
    func bootstrap() async {
        guard phase == .splash else { return }
        AuthService.shared.clearLocalCredentials()
        session.clear()
        repo.logout()
        try? await Task.sleep(nanoseconds: 700_000_000)
        phase = .login
    }

    /// Appelé quand l'app passe en arrière-plan : la session est fermée immédiatement.
    func lockOnBackground() {
        guard phase == .main, !signingIn else { return }
        AuthService.shared.clearLocalCredentials()
        session.clear()
        repo.logout()
        user = nil
        toast = nil
        phase = .login
    }

    func didLogin(_ u: User) {
        user = u
        phase = .main
        flushWebhooks()
        Task { await syncAssets(silent: true) }
    }

    @Published var syncing = false
    @Published var lastSync: Date?

    /// Jira Assets → inventaire (source de vérité). `silent` : pas de message si tout va bien.
    func syncAssets(silent: Bool) async {
        guard JiraAssetsClient.shared.isConfigured, !syncing else { return }
        syncing = true
        defer { syncing = false }
        guard let r = try? await repo.syncFromJiraAssets() else {
            if !silent { show(L("Synchronisation impossible"), style: .urgent) }
            return
        }
        if r.success {
            lastSync = Date()
            dataChanged()
            if !silent {
                show(L("✅ Inventaire synchronisé : %@ modèles, %@ unités", String(r.products), String(r.units)), style: .success)
            }
        } else if !silent {
            show(L("Synchronisation impossible") + " — " + (r.error ?? ""), style: .urgent)
        }
    }

    /** Renvoie les automatisations n8n restées en attente (hors ligne / workflow inactif). */
    func flushWebhooks() {
        let r = repo
        Task.detached(priority: .utility) { _ = try? await r.flushOutbox() }
    }

    func refreshUser() { user = repo.currentUser }

    func dataChanged() {
        refreshUser()
        dataVersion += 1
        Task { await WidgetBridge.update(repo: repo) }
    }

    func logout() async {
        await AuthService.shared.signOut()
        session.clear()
        repo.logout()
        user = nil
        phase = .login
    }

    func show(_ message: String, style: Toast.Style = .info) {
        let t = Toast(message: message, style: style)
        toast = t
        Haptics.tap()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            if self?.toast?.id == t.id { self?.toast = nil }
        }
    }
}

struct Toast: Equatable {
    enum Style { case info, success, urgent }
    let id = UUID()
    let message: String
    let style: Style
}

/// Snackbar Vista (INFO navy / SUCCESS menthe / URGENT corail).
struct ToastView: View {
    let toast: Toast
    var body: some View {
        Text(toast.message)
            .font(.subheadline.weight(.semibold))
            .foregroundColor(toast.style == .success ? .vistaNavy : .white)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(bg, in: RoundedRectangle(cornerRadius: 12))
            .shadow(radius: 8)
            .padding(.horizontal, 12)
    }
    private var bg: Color {
        switch toast.style {
        case .info: return .vistaNavy
        case .success: return .vistaMint
        case .urgent: return .vistaCoral
        }
    }
}
