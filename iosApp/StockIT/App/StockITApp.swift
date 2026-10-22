import SwiftUI
import Shared

@main
struct StockITApp: App {
    @StateObject private var app = AppState()
    @AppStorage("darkMode") private var darkMode = false
    /// Langue choisie dans Paramètres : changer la valeur retraduit toute l'interface immédiatement.
    @AppStorage(AppLanguage.storageKey) private var language = AppLanguage.current.rawValue

    init() {
        AppConfig.applyInfoPlistOverrides()
        #if DEBUG
        // Tests automatiques : langue de départ imposée (une seule fois, au lancement).
        if let l = ProcessInfo.processInfo.environment["STOCKIT_RESET_LANGUAGE"] {
            UserDefaults.standard.set(l, forKey: AppLanguage.storageKey)
        }
        #endif
        IosStockIt.shared.setup(deviceName: "Apple \(UIDevice.current.model) iOS \(UIDevice.current.systemVersion)")
        StockItConfig.shared.testMode = UserDefaults.standard.bool(forKey: "testMode")
        AppLanguage.applyToShared()
        NotificationService.requestPermission()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .environment(\.locale, Locale(identifier: language))
                // Reconstruit les vues : les textes construits en code (L(…)) et les données
                // venant du module partagé sont rechargés dans la nouvelle langue.
                .id(language)
                .onChange(of: language) { _ in
                    AppLanguage.applyToShared()
                    app.reopenSettings = true
                    app.dataChanged()
                }
                .preferredColorScheme(darkMode ? .dark : .light)
                .tint(.vistaBlue)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var app: AppState
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack(alignment: .bottom) {
            switch app.phase {
            case .splash: SplashView()
            case .login: LoginView()
            case .main: MainTabView()
            }
            if app.phase == .main && TestModeBanner.isOn {
                TestModeBanner().frame(maxHeight: .infinity, alignment: .top)
            }
            if let t = app.toast {
                ToastView(toast: t)
                    .padding(.bottom, 90)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.25), value: app.phase)
        .animation(.spring(), value: app.toast?.id)
        .task { await app.bootstrap() }
        .onChange(of: scenePhase) { phase in
            // Quitter l'app (écran d'accueil, autre app, verrouillage) = déconnexion.
            if phase == .background { app.lockOnBackground() }
            if phase == .active {
                app.flushWebhooks()
                if app.phase == .main { Task { await app.syncAssets(silent: true) } }
            }
        }
    }
}
