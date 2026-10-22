import Foundation
import Shared

/// Surcharge des clés partagées depuis Info.plist → clé `StockITConfig` (dictionnaire).
/// Les valeurs par défaut sont injectées au build depuis `.env.sh` (module shared).
enum AppConfig {
    static func applyInfoPlistOverrides() {
        guard let dict = Bundle.main.object(forInfoDictionaryKey: "StockITConfig") as? [String: Any] else { return }
        for (k, v) in dict {
            if let s = v as? String { StockItConfig.shared.applyOverride(key: k, value: s) }
        }
        // Backend réclamations : sur simulateur, localhost remplace 10.0.2.2 (émulateur Android).
        #if targetEnvironment(simulator)
        if StockItConfig.shared.apiBaseUrl.isEmpty {
            StockItConfig.shared.applyOverride(key: "STOCKIT_API_BASE_URL", value: "http://localhost/api/")
        }
        #endif
    }

    /// Valeur d'Info.plist si renseignée, sinon celle de .env.sh (module partagé).
    private static func plist(_ key: String, _ fallback: String) -> String {
        let v = (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        return v.isEmpty ? fallback : v
    }
    static var auth0ClientId: String { plist("Auth0ClientId", StockItConfig.shared.auth0ClientId) }
    static var auth0Domain: String { plist("Auth0Domain", StockItConfig.shared.auth0Domain) }

    static var auth0Connection: String { plist("Auth0Connection", StockItConfig.shared.auth0Connection) }

    static var auth0CallbackURL: URL? {
        let v = plist("Auth0CallbackURL", StockItConfig.shared.auth0CallbackUrl); return v.isEmpty ? nil : URL(string: v)
    }
    static var auth0LogoutURL: URL? {
        let v = plist("Auth0LogoutURL", StockItConfig.shared.auth0LogoutUrl); return v.isEmpty ? nil : URL(string: v)
    }

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }
}
