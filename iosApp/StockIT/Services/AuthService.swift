import Foundation
import Auth0
import Shared

/// Vista SSO via Auth0 Universal Login (portage de util/Auth0Manager.java).
/// Les credentials sont stockés dans le Keychain (CredentialsManager).
final class AuthService {
    static let shared = AuthService()
    private let scope = "openid profile email offline_access"

    private lazy var credentials: CredentialsManager? = {
        guard isConfigured else { return nil }
        let api = Auth0.authentication(clientId: AppConfig.auth0ClientId, domain: AppConfig.auth0Domain)
        return CredentialsManager(authentication: api)
    }()

    var isConfigured: Bool {
        let d = AppConfig.auth0Domain, c = AppConfig.auth0ClientId
        return !d.isEmpty && !c.isEmpty && !d.contains("REPLACE") && !c.contains("REPLACE")
    }

    struct Profile { let username: String; let email: String?; let role: String }

    /// Session valide (ou rafraîchissable) ? Purge le stockage sinon.
    func hasValidSession() async -> Bool {
        guard let cm = credentials, cm.canRenew() || cm.hasValid() else { return false }
        do {
            let c = try await cm.credentials()
            StockIt.shared.setAccessToken(token: c.accessToken)
            return true
        } catch {
            _ = cm.clear()
            return false
        }
    }

    func signIn() async throws -> Profile {
        guard isConfigured, let cm = credentials else {
            throw NSError(domain: "StockIT", code: 1, userInfo: [NSLocalizedDescriptionKey:
                L("Auth0 non configuré : renseignez Auth0Domain et Auth0ClientId dans Info.plist.")])
        }
        // Session éphémère : aucun cookie Okta n'est conservé → identifiants redemandés à chaque fois.
        var wa = Auth0.webAuth(clientId: AppConfig.auth0ClientId, domain: AppConfig.auth0Domain)
            .scope(scope)
            .useEphemeralSession()
        let audience = StockItConfig.shared.auth0Audience
        if !audience.isEmpty { wa = wa.audience(audience) }
        if !AppConfig.auth0Connection.isEmpty { wa = wa.connection(AppConfig.auth0Connection) }
        if let cb = AppConfig.auth0CallbackURL { wa = wa.redirectURL(cb) }
        let c = try await wa.start()
        _ = cm.store(credentials: c)
        StockIt.shared.setAccessToken(token: c.accessToken)

        let claims = Self.decodeJWT(c.idToken)
        let email = claims["email"] as? String
        let username = [claims["nickname"] as? String, claims["name"] as? String, email]
            .compactMap { $0 }.first(where: { !$0.isEmpty }) ?? "vista.user"
        var flat: [String: String] = [:]
        for (k, v) in claims {
            if let arr = v as? [Any] { flat[k] = arr.map { "\($0)" }.joined(separator: ",") }
            else { flat[k] = "\(v)" }
        }
        var role = RoleResolver.shared.resolve(claims: flat)
        if role == "USER" { role = StockItConfig.shared.ssoDefaultRole }
        StockIt.shared.session.saveSsoSession(username: username, email: email, role: role)
        return Profile(username: username, email: email, role: role)
    }

    /// Efface les tokens du Keychain sans ouvrir de navigateur (utilisable en arrière-plan).
    func clearLocalCredentials() {
        StockIt.shared.setAccessToken(token: nil)
        _ = credentials?.clear()
    }

    func signOut() async {
        StockIt.shared.setAccessToken(token: nil)
        guard isConfigured, let cm = credentials else { return }
        // Session navigateur éphémère : rien à purger côté Okta, on efface juste les tokens locaux.
        _ = cm.clear()
    }

    private static func decodeJWT(_ jwt: String) -> [String: Any] {
        let parts = jwt.split(separator: ".")
        guard parts.count > 1 else { return [:] }
        var b64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }
}
