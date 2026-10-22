package com.example.stockit.shared.session

/** Stockage clé/valeur persistant (SharedPreferences Android, NSUserDefaults iOS). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun clear()
}

/**
 * Session locale minimale — portage de util/SessionManager.java (mêmes clés,
 * fichier/suite "stockit_session"). Les tokens restent gérés par le SDK Auth0
 * (Keychain sur iOS, Keystore sur Android).
 */
class SessionStore(private val kv: KeyValueStore) {

    val isLoggedIn: Boolean get() = kv.getBoolean(KEY_LOGGED_IN, false)
    val username: String? get() = kv.getString(KEY_USERNAME)
    val email: String? get() = kv.getString(KEY_EMAIL)
    val role: String get() = kv.getString(KEY_ROLE) ?: "USER"
    val provider: String get() = kv.getString(KEY_PROVIDER) ?: "local"

    /** Session issue de l'IdP (Auth0 / Vista SSO). */
    fun saveSsoSession(username: String, email: String?, role: String?) {
        kv.putBoolean(KEY_LOGGED_IN, true)
        kv.putString(KEY_USERNAME, username)
        kv.putString(KEY_EMAIL, email)
        kv.putString(KEY_ROLE, role ?: "USER")
        kv.putString(KEY_PROVIDER, "auth0")
    }

    fun saveLocalSession(username: String, role: String?) {
        kv.putBoolean(KEY_LOGGED_IN, true)
        kv.putString(KEY_USERNAME, username)
        kv.putString(KEY_EMAIL, null)
        kv.putString(KEY_ROLE, role ?: "USER")
        kv.putString(KEY_PROVIDER, "local")
    }

    fun clear() = kv.clear()

    companion object {
        const val PREFS_NAME = "stockit_session"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_USERNAME = "username"
        private const val KEY_EMAIL = "email"
        private const val KEY_ROLE = "role"
        private const val KEY_PROVIDER = "provider"
    }
}

/**
 * Extraction du rôle depuis les claims Auth0 (même convention que Auth0Manager Android :
 * `https://vista.com/roles`, `https://vista.com/groups`, `roles`, `groups`).
 * `claims` : valeurs déjà converties en texte (listes jointes par des virgules).
 */
object RoleResolver {
    private val CANDIDATE_KEYS = listOf("https://vista.com/roles", "https://vista.com/groups", "roles", "groups")

    fun resolve(claims: Map<String, String>): String {
        for (k in CANDIDATE_KEYS) {
            val v = claims[k]?.uppercase() ?: continue
            when {
                "ADMIN" in v -> return "ADMIN"
                "MANAGER" in v -> return "MANAGER"
                "TECHNICIEN" in v || "TECH" in v -> return "TECHNICIEN"
            }
        }
        return "USER"
    }
}
