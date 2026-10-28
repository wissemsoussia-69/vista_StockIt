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
 * Session locale minimale — portage de util/SessionManager.java (mêmes clés).
 * Sur iOS, le stockage est en mémoire uniquement (la session ne survit pas au lancement) :
 * rien n'est écrit dans NSUserDefaults. Les tokens restent gérés par le SDK Auth0 (Keychain).
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
 * Rôle StockIT à partir des claims Auth0 (déjà convertis en texte, listes jointes par des virgules).
 *
 * Correspondance EXACTE avec une liste fermée de groupes — plus aucune recherche de sous-chaîne
 * (« biotech » ne donne plus TECHNICIEN, « not-an-admin » ne donne plus ADMIN) :
 *  - groupes `stockit-admin`, `stockit-manager`, `stockit-technicien` (alias -tech / -technician), dans tout claim ;
 *  - noms de rôle nus `ADMIN`, `MANAGER`, `TECHNICIEN` uniquement dans le claim namespacé `https://vista.com/roles`.
 * Le rôle le plus élevé l'emporte. Le rôle par défaut ne peut être que USER ou TECHNICIEN.
 */
object RoleResolver {
    private const val NAMESPACED_ROLES = "https://vista.com/roles"
    private val CANDIDATE_KEYS = listOf(NAMESPACED_ROLES, "https://vista.com/groups", "roles", "groups")
    private val PRIORITY = listOf("ADMIN", "MANAGER", "TECHNICIEN")

    private val GROUPS = mapOf(
        "stockit-admin" to "ADMIN", "stockit-admins" to "ADMIN",
        "stockit-manager" to "MANAGER", "stockit-managers" to "MANAGER",
        "stockit-technicien" to "TECHNICIEN", "stockit-techniciens" to "TECHNICIEN",
        "stockit-tech" to "TECHNICIEN", "stockit-technician" to "TECHNICIEN", "stockit-technicians" to "TECHNICIEN",
    )

    /** Rôles qu'une configuration peut attribuer par défaut (jamais ADMIN / MANAGER). */
    private val ALLOWED_DEFAULTS = setOf("USER", "TECHNICIEN")

    fun resolve(claims: Map<String, String>, defaultRole: String = "USER"): String {
        val found = mutableSetOf<String>()
        for (k in CANDIDATE_KEYS) {
            val tokens = claims[k]?.split(',', ' ', ';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: continue
            for (t in tokens) {
                GROUPS[t.lowercase()]?.let { found += it }
                if (k == NAMESPACED_ROLES && t.uppercase() in PRIORITY) found += t.uppercase()
            }
        }
        PRIORITY.firstOrNull { it in found }?.let { return it }
        return defaultRole.trim().uppercase().takeIf { it in ALLOWED_DEFAULTS } ?: "USER"
    }
}

/**
 * Contrôle des claims de l'ID token Auth0, en complément de la vérification de signature faite par Auth0.swift
 * (défense en profondeur) : émetteur = tenant configuré, audience = client id, token non expiré, `sub` présent.
 * Retourne null si valide, sinon la raison du refus.
 */
object IdTokenValidator {
    private const val CLOCK_SKEW_SEC = 60L

    fun validate(claims: Map<String, String>, domain: String, clientId: String, nowSec: Long): String? {
        val expectedIss = "https://" + domain.trim().removePrefix("https://").trimEnd('/') + "/"
        if (claims["iss"] != expectedIss) return "invalid_issuer"
        val aud = claims["aud"]?.split(',')?.map { it.trim() }.orEmpty()
        if (clientId.isBlank() || clientId !in aud) return "invalid_audience"
        val exp = claims["exp"]?.toLongOrNull() ?: return "missing_expiry"
        if (exp + CLOCK_SKEW_SEC < nowSec) return "token_expired"
        if (claims["sub"].isNullOrBlank()) return "missing_subject"
        return null
    }
}
