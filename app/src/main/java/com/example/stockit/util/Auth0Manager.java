package com.example.stockit.util;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.auth0.android.Auth0;
import com.auth0.android.authentication.AuthenticationException;
import com.auth0.android.authentication.storage.CredentialsManagerException;
import com.auth0.android.authentication.storage.SecureCredentialsManager;
import com.auth0.android.authentication.storage.SharedPreferencesStorage;
import com.auth0.android.callback.Callback;
import com.auth0.android.provider.WebAuthProvider;
import com.auth0.android.result.Credentials;
import com.auth0.android.result.UserProfile;
import com.example.stockit.BuildConfig;
import com.example.stockit.R;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * StockIT — Passerelle Auth0 (Vista SSO).
 *
 * Fine couche autour du SDK <a href="https://auth0.com/docs/quickstart/native/android">Auth0
 * Android</a> qui centralise :
 *   - la configuration (lue depuis {@code strings.xml} :
 *     {@code com_auth0_domain}, {@code com_auth0_client_id}, {@code com_auth0_scheme}),
 *   - le déclenchement d'Universal Login via Chrome Custom Tabs
 *     ({@link WebAuthProvider#login(Auth0)}),
 *   - la persistance chiffrée (Android Keystore) des credentials via
 *     {@link SecureCredentialsManager},
 *   - la synchronisation avec {@link SessionManager} pour l'aiguillage du splash.
 */
public final class Auth0Manager {

    private static final String TAG = "Auth0Manager";
    // Pas de refresh token persistant : offline_access retiré (TM-25, même décision
    // que l'app iOS). Les tokens sont effacés à chaque lancement (clearLocalCredentials).
    private static final String SCOPE = "openid profile email";

    private static volatile Auth0Manager INSTANCE;

    private final Context appContext;
    @Nullable private final Auth0 account;
    @Nullable private volatile SecureCredentialsManager credentialsManager;
    private final boolean configured;

    private Auth0Manager(Context ctx) {
        this.appContext = ctx.getApplicationContext();

        String domain   = appContext.getString(R.string.com_auth0_domain);
        String clientId = appContext.getString(R.string.com_auth0_client_id);
        boolean ok = !TextUtils.isEmpty(domain)
                && !TextUtils.isEmpty(clientId)
                && !domain.contains("REPLACE")
                && !clientId.contains("REPLACE");

        Auth0 acc = null;
        SecureCredentialsManager mgr = null;
        try {
            if (ok) {
                acc = Auth0.getInstance(clientId, domain);
                mgr = new SecureCredentialsManager(
                        appContext,
                        acc,
                        new SharedPreferencesStorage(appContext));
            } else {
                Log.w(TAG, "Auth0 SSO non configuré : com_auth0_domain / com_auth0_client_id manquants ou placeholders.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Impossible d'initialiser Auth0 : " + t.getMessage(), t);
            ok = false;
        }
        this.account = acc;
        this.credentialsManager = mgr;
        this.configured = ok;
    }

    public static Auth0Manager get(Context context) {
        if (INSTANCE == null) {
            synchronized (Auth0Manager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Auth0Manager(context);
                }
            }
        }
        return INSTANCE;
    }

    /** @return true si les valeurs Auth0 sont présentes dans strings.xml. */
    public boolean isConfigured() {
        return configured;
    }

    // ---------------------------------------------------------------------
    // Biométrie (opt-in via BuildConfig.AUTH0_REQUIRE_BIOMETRIC)
    // ---------------------------------------------------------------------

    private volatile boolean biometricArmed = false;

    /**
     * Si {@link BuildConfig#AUTH0_REQUIRE_BIOMETRIC} vaut {@code true}, remplace
     * le {@link SecureCredentialsManager} par une variante qui exige une
     * authentification biométrique (Face/Empreinte, avec fallback PIN/pattern)
     * à chaque déchiffrement des tokens.
     *
     * Depuis Auth0.Android 3.x la biométrie ne s'active plus via une méthode
     * runtime : elle doit être passée au constructeur du manager via
     * {@code LocalAuthenticationOptions}. On rebâtit donc l'instance ici, une
     * fois qu'on dispose d'une {@link FragmentActivity} (dans {@code SplashActivity}).
     *
     * Idempotent. No-op si Auth0 n'est pas configuré, si le flag est off, ou
     * si l'appareil ne dispose pas de biométrie (le manager déclenchera alors
     * un fallback device credential).
     */
    public void enableBiometricIfRequested(@NonNull androidx.fragment.app.FragmentActivity activity) {
        if (!BuildConfig.AUTH0_REQUIRE_BIOMETRIC) return;
        if (account == null || biometricArmed) return;
        try {
            com.auth0.android.authentication.storage.LocalAuthenticationOptions opts =
                    new com.auth0.android.authentication.storage.LocalAuthenticationOptions.Builder()
                            .setTitle(activity.getString(R.string.app_name))
                            .setDescription(AppLanguage.L("Confirmez votre identité pour accéder à StockIT"))
                            .setNegativeButtonText(AppLanguage.L("Annuler"))
                            .setDeviceCredentialFallback(true)
                            .build();
            credentialsManager = new SecureCredentialsManager(
                    appContext,
                    account,
                    new SharedPreferencesStorage(appContext),
                    activity,
                    opts);
            biometricArmed = true;
        } catch (Throwable t) {
            Log.w(TAG, "Impossible d'armer la biométrie sur les credentials : " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Vérification de session (utilisée par le splash)
    // ---------------------------------------------------------------------

    /**
     * Interroge le {@link SecureCredentialsManager} : si des credentials existent
     * et sont valides (ou peuvent être rafraîchis via refresh_token), le résultat
     * est {@code true} et {@link SessionManager} est mis à jour avec le profil.
     */
    public void hasValidSession(@NonNull SessionCheckCallback callback) {
        if (credentialsManager == null) {
            callback.onResult(false);
            return;
        }
        try {
            credentialsManager.getCredentials(new Callback<Credentials, CredentialsManagerException>() {
                @Override
                public void onSuccess(Credentials result) {
                    syncSession(result);
                    callback.onResult(true);
                }

                @Override
                public void onFailure(@NonNull CredentialsManagerException error) {
                    Log.d(TAG, "Pas de session valide : " + error.getMessage());
                    // Credentials corrompus / refresh révoqué / expiration définitive :
                    // on purge le storage local pour éviter de rester bloqué dans un
                    // état "pourri" au prochain démarrage.
                    try {
                        credentialsManager.clearCredentials();
                    } catch (Throwable ignored) {
                        // Best-effort : le SDK peut lever si le storage est déjà vide.
                    }
                    SessionManager.get(appContext).clear();
                    callback.onResult(false);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "hasValidSession erreur : " + t.getMessage(), t);
            try {
                credentialsManager.clearCredentials();
            } catch (Throwable ignored) { }
            SessionManager.get(appContext).clear();
            callback.onResult(false);
        }
    }

    // ---------------------------------------------------------------------
    // Login / Logout
    // ---------------------------------------------------------------------

    /**
     * Demande au {@link SecureCredentialsManager} les credentials courants
     * (rafraîchis si expirés). Utilisé par {@link AuthBearerInterceptor} pour
     * injecter un {@code Authorization: Bearer} sur les appels backend.
     *
     * @return {@code true} si la demande a bien été transmise (Auth0 configuré),
     *         {@code false} sinon — dans ce cas le callback n'est PAS invoqué.
     */
    public boolean tryGetAccessToken(
            @NonNull Callback<Credentials, CredentialsManagerException> callback) {
        SecureCredentialsManager mgr = credentialsManager;
        if (mgr == null) return false;
        try {
            mgr.getCredentials(callback);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tryGetAccessToken a levé : " + t.getMessage());
            return false;
        }
    }

    /**
     * Lance Auth0 Universal Login dans un Chrome Custom Tab.
     * Doit être appelé depuis une {@link Activity} au premier plan.
     */
    public void signIn(@NonNull Activity activity, @NonNull SignInCallback callback) {
        if (account == null || credentialsManager == null) {
            callback.onError(AppLanguage.L("Auth0 non configuré : renseignez com_auth0_domain et com_auth0_client_id."));
            return;
        }

        String scheme = appContext.getString(R.string.com_auth0_scheme);
        com.auth0.android.provider.WebAuthProvider.Builder builder =
                WebAuthProvider.login(account)
                        .withScheme(scheme)
                        .withScope(SCOPE);
        // Sans audience Auth0 renvoie un access_token opaque inutilisable
        // côté backend. Quand AUTH0_AUDIENCE est renseigné (env var), on
        // demande un JWT d'access token destiné à cette API.
        if (!TextUtils.isEmpty(BuildConfig.AUTH0_AUDIENCE)) {
            builder = builder.withAudience(BuildConfig.AUTH0_AUDIENCE);
        }
        // Connexion Okta Vista (identique iOS : Auth0Connection = vista-okta).
        String connection = appContext.getString(R.string.com_auth0_connection);
        if (!TextUtils.isEmpty(connection)) {
            builder = builder.withConnection(connection);
        }
        // Force la saisie des identifiants (équivalent de la session éphémère iOS).
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("prompt", "login");
        builder = builder.withParameters(params);
        builder.start(activity, new Callback<Credentials, AuthenticationException>() {
                    @Override
                    public void onSuccess(Credentials result) {
                        // TM-24 : re-vérification des claims de l'ID token avant d'accepter la session.
                        String reject = validateIdTokenClaims(result,
                                appContext.getString(R.string.com_auth0_domain),
                                appContext.getString(R.string.com_auth0_client_id));
                        if (reject != null) {
                            Log.w(TAG, "ID token refusé : " + reject);
                            try {
                                credentialsManager.clearCredentials();
                            } catch (Throwable ignored) { }
                            callback.onError(AppLanguage.L("Session refusée (jeton invalide : %@)", reject));
                            return;
                        }
                        try {
                            credentialsManager.saveCredentials(result);
                        } catch (Throwable t) {
                            Log.w(TAG, "saveCredentials a échoué : " + t.getMessage());
                        }
                        String[] info = syncSession(result);
                        // Identité stable : le sujet Auth0 (« sub »), jamais le nom affiché (TM-22).
                        String subject = null;
                        try {
                            com.auth0.android.result.UserProfile p = result.getUser();
                            if (p != null) subject = p.getId();
                        } catch (Throwable ignored) { }
                        if (subject == null || subject.trim().isEmpty()) subject = extractSubjectFromIdToken(result);
                        callback.onSuccess(info[0], info[1], info[2], subject);
                    }

                    @Override
                    public void onFailure(@NonNull AuthenticationException error) {
                        String msg = error.getMessage();
                        if (TextUtils.isEmpty(msg)) msg = error.getDescription();
                        // Code + description Auth0 (ex. access_denied, unauthorized_client) : cause exacte.
                        String code = error.getCode();
                        String desc = error.getDescription();
                        Log.e(TAG, "Login Auth0 échoué : code=" + code + " description=" + desc
                                + " status=" + error.getStatusCode() + " msg=" + msg, error);
                        if (!TextUtils.isEmpty(code) && !"a0.sdk.internal_error.unknown".equals(code)) {
                            msg = (TextUtils.isEmpty(desc) ? msg : desc) + " (" + code + ")";
                        }
                        callback.onError(msg != null ? msg : AppLanguage.L("Erreur Auth0 inconnue."));
                    }
                });
    }

    /**
     * Déconnexion complète : révoque la session côté Auth0 puis efface le stockage local.
     * Le callback {@code onComplete} est invoqué sur le thread principal, que la
     * déconnexion réussisse ou échoue (comme ça l'UI peut toujours rediriger).
     */
    public void signOut(@NonNull Activity activity, @Nullable Runnable onComplete) {
        SessionManager.get(appContext).clear();

        if (account == null || credentialsManager == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String scheme = appContext.getString(R.string.com_auth0_scheme);
        WebAuthProvider.logout(account)
                .withScheme(scheme)
                .start(activity, new Callback<Void, AuthenticationException>() {
                    @Override
                    public void onSuccess(Void result) {
                        try {
                            credentialsManager.clearCredentials();
                        } catch (Throwable t) {
                            Log.w(TAG, "clearCredentials : " + t.getMessage());
                        }
                        if (onComplete != null) onComplete.run();
                    }

                    @Override
                    public void onFailure(@NonNull AuthenticationException error) {
                        Log.w(TAG, "Logout Auth0 : " + error.getMessage());
                        // Même en cas d'échec réseau on veut vider le local
                        // et rediriger l'utilisateur vers le login.
                        try {
                            credentialsManager.clearCredentials();
                        } catch (Throwable ignored) { }
                        if (onComplete != null) onComplete.run();
                    }
                });
    }

    /**
     * Efface les tokens locaux sans ouvrir de navigateur (équivalent iOS
     * {@code clearLocalCredentials}) : utilisé au lancement pour forcer une
     * nouvelle connexion Okta à chaque ouverture de l'app.
     */
    public void clearLocalCredentials() {
        SessionManager.get(appContext).clear();
        if (credentialsManager == null) return;
        try {
            credentialsManager.clearCredentials();
        } catch (Throwable t) {
            Log.w(TAG, "clearLocalCredentials : " + t.getMessage());
        }
    }

    /** Variante sans callback pour les appels "fire-and-forget". */
    public void signOut(@NonNull Activity activity) {
        signOut(activity, null);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Extrait username / email / rôle depuis les credentials Auth0 et met à jour
     * {@link SessionManager}. Retourne {@code [username, email, role]}.
     */
    private String[] syncSession(@NonNull Credentials credentials) {
        UserProfile profile = credentials.getUser();
        String email = profile != null ? profile.getEmail() : null;
        String username = firstNonEmpty(
                profile != null ? profile.getNickname() : null,
                profile != null ? profile.getName() : null,
                email,
                "vista.user");
        // extractRole applique déjà le rôle par défaut (borné USER/TECHNICIEN
        // par RoleResolver) : aucune surcharge directe de la configuration.
        String role = extractRole(profile);

        SessionManager.get(appContext).saveSsoSession(username, email, role);
        return new String[] { username, email, role };
    }

    /**
     * Cherche un rôle dans les extra-claims (roles / groups) du profil Auth0.
     *
     * Correspondance EXACTE avec une liste fermée de groupes (plus aucune recherche
     * de sous-chaîne) : groupes `stockit-admin`, `stockit-manager`, `stockit-technicien`
     * (alias -tech / -technician) dans tout claim ; noms nus ADMIN / MANAGER /
     * TECHNICIEN uniquement dans le claim namespacé `https://vista.com/roles`.
     * Le rôle le plus élevé l'emporte ; le rôle par défaut ne peut jamais être
     * ADMIN ou MANAGER (cf. RoleResolver du module partagé, couvert par AuthSecurityTest).
     */
    public static String extractRole(@Nullable UserProfile profile) {
        if (profile == null) return "USER";
        Map<String, Object> extra = profile.getExtraInfo();
        if (extra == null || extra.isEmpty()) return "USER";

        // Conversion en Map<String, String> pour RoleResolver (listes jointes par des virgules).
        Map<String, String> claims = new java.util.HashMap<>();
        for (String key : CANDIDATE_KEYS) {
            String text = flatten(extra.get(key));
            if (!text.isEmpty()) claims.put(key, text);
        }
        if (claims.isEmpty()) return "USER";
        return com.example.stockit.shared.session.RoleResolver.INSTANCE.resolve(claims, defaultSsoRole());
    }

    private static final String[] CANDIDATE_KEYS = {
            "https://vista.com/roles",
            "https://vista.com/groups",
            "roles",
            "groups"
    };

    /** Valeur de claim → texte (liste jointe par des virgules, null → vide). */
    private static String flatten(Object value) {
        if (value == null) return "";
        if (value instanceof List<?>) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (List<?>) value) {
                if (item == null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(String.valueOf(item));
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /** Rôle par défaut (STOCKIT_SSO_DEFAULT_ROLE) : USER ou TECHNICIEN uniquement. */
    private static String defaultSsoRole() {
        try {
            String def = SharedBridge.ssoDefaultRole();
            if (def != null && !def.trim().isEmpty()) return def.trim().toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) { }
        return "USER";
    }

    /**
     * Rôle d'une valeur de claim (liste, map ou chaîne) : même politique EXACTE que
     * {@link #extractRole} — groupes connus uniquement, noms nus dans le claim
     * namespacé uniquement. Public statique pour les tests (Auth0RoleMappingTest).
     */
    @Nullable
    public static String matchRole(@Nullable Object value) {
        if (value == null) return null;
        // Une Map de claims est résolue directement (clés candidates uniquement).
        if (value instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) value;
            Map<String, String> claims = new java.util.HashMap<>();
            for (String key : CANDIDATE_KEYS) {
                String text = flatten(m.get(key));
                if (!text.isEmpty()) claims.put(key, text);
            }
            if (claims.isEmpty()) return null;
            String r = com.example.stockit.shared.session.RoleResolver.INSTANCE.resolve(claims, "USER");
            return "USER".equals(r) ? null : r;
        }
        // Valeur brute : traitée comme un claim « groups » (noms de groupes exacts).
        String text = flatten(value);
        if (text.isEmpty()) return null;
        Map<String, String> groups = new java.util.HashMap<>();
        groups.put("groups", text);
        String r = com.example.stockit.shared.session.RoleResolver.INSTANCE.resolve(groups, "USER");
        return "USER".equals(r) ? null : r;
    }

    /** Sujet (« sub ») décodé du payload de l'ID token (base64url, segment du milieu). */
    private static String extractSubjectFromIdToken(@NonNull Credentials credentials) {
        try {
            String idToken = credentials.getIdToken();
            if (idToken == null) return null;
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return null;
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            org.json.JSONObject claims = new org.json.JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            return claims.optString("sub", null);
        } catch (Throwable t) {
            Log.w(TAG, "ID token illisible : " + t.getMessage());
            return null;
        }
    }

    /**
     * Défense en profondeur (TM-24) : re-vérification des claims de l'ID token —
     * émetteur = tenant configuré, audience = client id, expiration (marge 60 s),
     * sujet présent. Le SDK Auth0 valide la signature ; on revalide ici les claims
     * avant d'accepter la session (même contrôle que l'app iOS, cf. IdTokenValidator).
     *
     * @return null si valide, sinon la raison du refus.
     */
    @Nullable
    public static String validateIdTokenClaims(@Nullable Credentials credentials, @NonNull String domain, @NonNull String clientId) {
        if (credentials == null) return "missing_credentials";
        try {
            String idToken = credentials.getIdToken();
            if (idToken == null) return "missing_id_token";
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return "malformed_id_token";
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            org.json.JSONObject o = new org.json.JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> claims = new java.util.HashMap<>();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = o.opt(k);
                if (v != null && !(v instanceof org.json.JSONObject) && !(v instanceof org.json.JSONArray)) {
                    claims.put(k, String.valueOf(v));
                }
            }
            return com.example.stockit.shared.session.IdTokenValidator.INSTANCE
                    .validate(claims, domain, clientId, System.currentTimeMillis() / 1000);
        } catch (Throwable t) {
            return "malformed_id_token";
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------

    public interface SessionCheckCallback {
        void onResult(boolean hasValidSession);
    }

    public interface SignInCallback {
        void onSuccess(String username, @Nullable String email, String role, @Nullable String subject);
        void onError(String message);
    }
}
