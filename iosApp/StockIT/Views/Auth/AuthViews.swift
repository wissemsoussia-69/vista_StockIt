import SwiftUI
import LocalAuthentication
import Shared

struct SplashView: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [.vistaBlue, .vistaBlueDeep], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "shippingbox.fill").font(.system(size: 64)).foregroundColor(.white)
                Text("StockIT - Vistaprint").font(.title.bold()).foregroundColor(.white)
                Text("Vérification de votre session…").foregroundColor(.white.opacity(0.85))
                ProgressView().tint(.white)
                Spacer().frame(height: 40)
                Text("Sécurisé par Auth0 · Vista Services").font(.footnote).foregroundColor(.white.opacity(0.7))
            }
        }
    }
}

/// LoginActivity : Vista SSO + accès de secours (démo hors ligne, builds Debug).
struct LoginView: View {
    @EnvironmentObject var app: AppState
    @State private var username = ""
    @State private var password = ""
    @State private var loading = false
    @State private var ssoStatus: String?
    @State private var profiles: [User] = []

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                VStack(spacing: 6) {
                    Image(systemName: "shippingbox.fill").font(.system(size: 50)).foregroundColor(.vistaBlue)
                    Text("StockIT").font(.largeTitle.bold()).foregroundColor(.vistaNavy)
                    Text("Accès restreint aux employés Vistaprint").font(.footnote).foregroundColor(.secondary)
                }
                .padding(.top, 30)

                VistaCard {
                    Text("Connexion Vista SSO").font(.headline)
                    Text("Authentification sécurisée avec vos identifiants Vistaprint (Auth0 Universal Login).")
                        .font(.subheadline).foregroundColor(.secondary)
                    Button("Se connecter avec Vista SSO") { Task { await sso() } }
                        .buttonStyle(PrimaryButtonStyle())
                        .disabled(!AuthService.shared.isConfigured || loading)
                    if !AuthService.shared.isConfigured {
                        Text("SSO non configuré. Renseignez Auth0Domain et Auth0ClientId dans Info.plist.")
                            .font(.caption).foregroundColor(.vistaCoral)
                    }
                    if let s = ssoStatus { Text(s).font(.caption).foregroundColor(.vistaCoral) }
                }

                #if DEBUG
                localFallback
                #endif

                if loading { ProgressView() }
            }
            .padding()
        }
        .background(Color.vistaBg.ignoresSafeArea())
        .task { await loadProfiles() }
    }

    private var localFallback: some View {
        VistaCard {
            Text("Qui êtes-vous ?").font(.headline)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 12) {
                ForEach(profiles) { u in
                    Button { Task { await biometricLogin(u) } } label: {
                        VStack {
                            avatar(u.username).frame(width: 56, height: 56).clipShape(Circle())
                            Text(u.username).font(.caption).foregroundColor(.primary)
                        }
                    }
                }
            }
            Text("Accès de secours (démo hors ligne)").font(.caption).foregroundColor(.secondary)
            // Accès de secours local : pas de proposition iCloud « Enregistrer le mot de passe ».
            TextField("Nom d'utilisateur", text: $username)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .textContentType(.oneTimeCode)
                .textFieldStyle(.roundedBorder)
            SecureField("Mot de passe", text: $password)
                .textContentType(.oneTimeCode)
                .textFieldStyle(.roundedBorder)
            Button("Connexion classique") {
                guard !username.isEmpty, !password.isEmpty else { app.show(L("Champs obligatoires manquants"), style: .urgent); return }
                Task { await localLogin(username, password) }
            }
            .buttonStyle(PrimaryButtonStyle(color: .vistaNavy))
        }
    }

    @ViewBuilder private func avatar(_ name: String) -> some View {
        let asset = "avatar_" + name.lowercased()
        if UIImage(named: asset) != nil {
            Image(asset).resizable().scaledToFill()
        } else {
            ZStack {
                Color.vistaBlue
                Text(String(name.prefix(1)).uppercased()).font(.title2.bold()).foregroundColor(.white)
            }
        }
    }

    private func loadProfiles() async {
        var list = (try? await app.repo.getUsers()) ?? []
        if list.count < 5 {
            _ = try? await app.repo.login(username: "admin", password: "admin123")
            app.repo.logout()
            list = (try? await app.repo.getUsers()) ?? []
        }
        profiles = list
    }

    private func sso() async {
        loading = true; ssoStatus = nil
        app.signingIn = true
        defer { loading = false; app.signingIn = false }
        do {
            let p = try await AuthService.shared.signIn()
            let u = try await app.repo.loginSso(username: p.username, role: p.role)
            app.didLogin(u)
        } catch {
            ssoStatus = L("Erreur SSO : %@", String(describing: error.localizedDescription))
        }
    }

    private func biometricLogin(_ u: User) async {
        let ctx = LAContext()
        var err: NSError?
        let policy: LAPolicy = .deviceOwnerAuthentication
        if ctx.canEvaluatePolicy(policy, error: &err) {
            do {
                let ok = try await ctx.evaluatePolicy(policy, localizedReason: L("Connexion pour %@", String(describing: u.username)))
                if ok { await localLogin(u.username, u.password) }
            } catch {
                app.show(L("Erreur empreinte : %@", String(describing: error.localizedDescription)), style: .urgent)
            }
        } else {
            await localLogin(u.username, u.password)
        }
    }

    private func localLogin(_ u: String, _ p: String) async {
        loading = true
        defer { loading = false }
        if let user = try? await app.repo.login(username: u, password: p) {
            app.session.saveLocalSession(username: user.username, role: user.role)
            app.didLogin(user)
        } else {
            app.show(L("Identifiants incorrects"), style: .urgent)
        }
    }
}
