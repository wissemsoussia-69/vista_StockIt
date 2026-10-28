package com.example.stockit.shared

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.session.IdTokenValidator
import com.example.stockit.shared.session.RoleResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Threat model T-01, T-02, T-05, T-06 — local login, password storage, SSO identity and roles. */
class AuthSecurityTest {

    @BeforeTest fun setUp() = resetGlobals()
    @AfterTest fun tearDown() = resetGlobals()

    private fun SqlDriver.passwords(): Map<String, String> = executeQuery(null, "SELECT username, password FROM users", { c ->
        val m = mutableMapOf<String, String>()
        while (c.next().value) m[c.getString(0)!!] = c.getString(1)!!
        QueryResult.Value(m)
    }, 0).value

    // ------------------------------------------------------------------ T-01 backdoor & seeding

    @Test
    fun hardcodedAdminBackdoorIsRejected() = runTest {
        val (repo, _) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        assertNull(repo.login("admin", "admin123"), "admin/admin123 must not open an admin session")
        assertNull(repo.currentUser)
        StockItConfig.localLoginEnabled = false
        assertNull(repo.login("admin", "admin123"))
    }

    @Test
    fun noDefaultAccountsAreSeeded() = runTest {
        val (repo, driver) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        repo.login("Eya", "pass123")
        repo.loginSso("auth0|admin-1", "Boss", "ADMIN")
        val names = repo.getUsers().map { it.username }
        assertEquals(listOf("Boss"), names, "only real accounts may exist, no demo team with shared passwords")
        assertFalse(driver.passwords().values.any { it == "pass123" || it == "admin123" })
    }

    // ------------------------------------------------------------------ T-02 password storage

    @Test
    fun localLoginIsDisabledUnlessExplicitlyEnabled() = runTest {
        val (repo, _) = newTestRepository()
        assertFalse(StockItConfig.localLoginEnabled, "local login must be off by default (Release builds)")
        assertFalse(repo.provisionLocalUser("tech", "Correct-Horse-1", "TECHNICIEN"))
        assertNull(repo.login("tech", "Correct-Horse-1"))
    }

    @Test
    fun localPasswordsAreStoredAsSaltedHashes() = runTest {
        val (repo, driver) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        assertTrue(repo.provisionLocalUser("tech", "Correct-Horse-1", "TECHNICIEN"))
        val stored = driver.passwords().getValue("tech")
        assertNotEquals("Correct-Horse-1", stored)
        assertTrue(stored.startsWith("pbkdf2-sha256$"), stored)

        assertEquals("TECHNICIEN", repo.login("tech", "Correct-Horse-1")?.role)
        repo.logout()
        assertNull(repo.login("tech", "correct-horse-1"))
        assertNull(repo.login("tech", ""))
    }

    @Test
    fun ssoAccountsHaveNoUsablePassword() = runTest {
        val (repo, driver) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        repo.loginSso("auth0|alice", "alice", "TECHNICIEN")
        repo.logout()
        assertEquals("", driver.passwords().getValue("alice"), "no predictable 'sso-auth0' password")
        assertNull(repo.login("alice", "sso-auth0"), "SSO users must not be able to log in through the local path")
        assertNull(repo.login("alice", ""))
    }

    @Test
    fun repeatedFailuresLockTheAccountTemporarily() = runTest {
        val (repo, _) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        repo.provisionLocalUser("tech", "Correct-Horse-1", "TECHNICIEN")
        repeat(5) { assertNull(repo.login("tech", "wrong-$it")) }
        assertNull(repo.login("tech", "Correct-Horse-1"), "brute force: the correct password is refused while locked")
    }

    @Test
    fun loginAttemptsAreAudited() = runTest {
        val (repo, _) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        repo.provisionLocalUser("boss", "Correct-Horse-1", "ADMIN")
        assertNull(repo.login("boss", "nope"))
        assertNotNull(repo.login("boss", "Correct-Horse-1"))
        val actions = repo.getAuditLogs().map { it.action }
        assertTrue("LOGIN_FAILED" in actions, actions.toString())
        assertTrue("LOGIN" in actions, actions.toString())
    }

    // ------------------------------------------------------------------ T-05 SSO identity

    @Test
    fun ssoUserCannotTakeOverALocalAccountWithTheSameName() = runTest {
        val (repo, _) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        repo.provisionLocalUser("admin", "Correct-Horse-1", "ADMIN")
        val local = repo.login("admin", "Correct-Horse-1")!!
        repo.logout()

        val sso = repo.loginSso("auth0|someone", "admin", "USER")
        assertNotEquals(local.id, sso.id, "SSO identity must not reuse the local row")
        assertEquals("USER", sso.role)
        repo.logout()
        assertEquals("ADMIN", repo.login("admin", "Correct-Horse-1")?.role, "local admin role must be untouched")
    }

    @Test
    fun ssoIdentityIsKeyedOnTheSubjectNotTheDisplayName() = runTest {
        val (repo, _) = newTestRepository()
        val a = repo.loginSso("auth0|1111", "Sam", "TECHNICIEN")
        val b = repo.loginSso("auth0|2222", "Sam", "USER")
        assertNotEquals(a.id, b.id, "two people with the same display name are two accounts")
        val renamed = repo.loginSso("auth0|1111", "Samuel", "TECHNICIEN")
        assertEquals(a.id, renamed.id, "a renamed user keeps their account (points, history)")
    }

    @Test
    fun unknownRoleFromTheTokenIsNotTrusted() = runTest {
        val (repo, _) = newTestRepository()
        assertEquals("USER", repo.loginSso("auth0|x", "x", "SUPERADMIN").role)
        assertEquals("USER", repo.loginSso("auth0|y", "y", "admin; DROP").role)
    }

    // ------------------------------------------------------------------ T-05 role mapping

    @Test
    fun roleMappingUsesExactGroupNames() {
        assertEquals("ADMIN", RoleResolver.resolve(mapOf("roles" to "stockit-admin,users")))
        assertEquals("MANAGER", RoleResolver.resolve(mapOf("https://vista.com/roles" to "MANAGER")))
        assertEquals("TECHNICIEN", RoleResolver.resolve(mapOf("https://vista.com/groups" to "everyone, stockit-technicien")))
        // highest privilege wins whatever the order
        assertEquals("ADMIN", RoleResolver.resolve(mapOf("roles" to "stockit-technicien,stockit-admin")))
    }

    @Test
    fun roleMappingIgnoresSubstrings() {
        assertEquals("USER", RoleResolver.resolve(mapOf("groups" to "biotech-team")), "'TECH' inside another group name")
        assertEquals("USER", RoleResolver.resolve(mapOf("roles" to "not-an-admin")))
        assertEquals("USER", RoleResolver.resolve(mapOf("roles" to "sysadmin-readonly")))
        assertEquals("USER", RoleResolver.resolve(mapOf("groups" to "admin")), "plain role names only count in the namespaced roles claim")
    }

    @Test
    fun defaultSsoRoleCanNeverGrantPrivilegedAccess() {
        assertEquals("USER", RoleResolver.resolve(emptyMap(), defaultRole = "ADMIN"))
        assertEquals("USER", RoleResolver.resolve(emptyMap(), defaultRole = "MANAGER"))
        assertEquals("TECHNICIEN", RoleResolver.resolve(emptyMap(), defaultRole = "TECHNICIEN"))
        assertEquals("USER", RoleResolver.resolve(emptyMap(), defaultRole = "whatever"))
        assertEquals("ADMIN", RoleResolver.resolve(mapOf("roles" to "stockit-admin"), defaultRole = "USER"))
    }

    // ------------------------------------------------------------------ T-06 ID token claims

    private val now = 1_800_000_000L
    private fun claims(vararg extra: Pair<String, String>) = mapOf(
        "iss" to "https://tenant.eu.auth0.com/", "aud" to "client-123", "sub" to "auth0|42",
        "exp" to (now + 600).toString(), "iat" to (now - 10).toString(),
    ) + extra

    @Test
    fun idTokenClaimsAreValidated() {
        val v = IdTokenValidator
        assertNull(v.validate(claims(), "tenant.eu.auth0.com", "client-123", now))
        assertNull(v.validate(claims("aud" to "other,client-123"), "tenant.eu.auth0.com", "client-123", now))
        assertNotNull(v.validate(claims("iss" to "https://evil.example/"), "tenant.eu.auth0.com", "client-123", now))
        assertNotNull(v.validate(claims("aud" to "someone-else"), "tenant.eu.auth0.com", "client-123", now))
        // 60 s clock-skew leeway (same default as Auth0.swift), not more.
        assertNull(v.validate(claims("exp" to (now - 60).toString()), "tenant.eu.auth0.com", "client-123", now))
        assertNotNull(v.validate(claims("exp" to (now - 61).toString()), "tenant.eu.auth0.com", "client-123", now))
        assertNotNull(v.validate(claims("exp" to (now - 3600).toString()), "tenant.eu.auth0.com", "client-123", now))
        assertNotNull(v.validate(claims("exp" to "soon"), "tenant.eu.auth0.com", "client-123", now))
        assertNotNull(v.validate(claims() - "sub", "tenant.eu.auth0.com", "client-123", now))
    }
}
