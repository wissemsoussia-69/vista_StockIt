package com.example.stockit.shared

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.db.StockItDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Databases already installed on devices (schema v1) must be cleaned on upgrade:
 * plaintext / predictable passwords removed, seeded admin demoted, SSO rows marked, secrets purged from the outbox.
 */
class MigrationTest {

    @BeforeTest fun setUp() = resetGlobals()
    @AfterTest fun tearDown() = resetGlobals()

    /** Exact v1 DDL of the tables touched by the migration (copied from the 1.0 release). */
    private val v1 = listOf(
        """CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, password TEXT NOT NULL,
           role TEXT NOT NULL, points INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 1, badges TEXT NOT NULL DEFAULT '')""",
        """CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, body TEXT NOT NULL,
           attempts INTEGER NOT NULL DEFAULT 0, lastError TEXT, createdAt INTEGER NOT NULL)""",
        """CREATE TABLE audit_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, userId TEXT, action TEXT NOT NULL, details TEXT,
           timestamp INTEGER NOT NULL, ipAddress TEXT, device TEXT)""",
        "INSERT INTO users(username, password, role, points, level, badges) VALUES ('admin', 'admin123', 'ADMIN', 0, 1, '')",
        "INSERT INTO users(username, password, role, points, level, badges) VALUES ('Eya', 'pass123', 'TECHNICIEN', 40, 1, '')",
        "INSERT INTO users(username, password, role, points, level, badges) VALUES ('alice', 'sso-auth0', 'MANAGER', 120, 2, 'Badge Bronze')",
        "INSERT INTO outbox(url, body, attempts, lastError, createdAt) VALUES ('https://n8n.test/e', '{\"qty\":1,\"secret\":\"old-secret\"}', 3, 'HTTP 503', 0)",
    )

    private fun SqlDriver.rows(sql: String): List<List<String?>> = executeQuery(null, sql, { c ->
        val out = mutableListOf<List<String?>>()
        while (c.next().value) out += (0 until 3).map { runCatching { c.getString(it) }.getOrNull() }
        QueryResult.Value(out)
    }, 0).value

    private fun migratedV1(): SqlDriver {
        val d = createEmptyTestDriver()
        v1.forEach { d.execute(null, it, 0) }
        StockItDatabase.Schema.migrate(d, 1, StockItDatabase.Schema.version)
        return d
    }

    @Test
    fun schemaVersionWasBumped() {
        assertTrue(StockItDatabase.Schema.version >= 2)
    }

    @Test
    fun migrationRemovesWeakPasswordsAndDemotesSeededAdmin() {
        val d = migratedV1()
        val users = d.rows("SELECT username, password, role FROM users ORDER BY id").associate { it[0] to (it[1] to it[2]) }
        assertEquals("" to "USER", users["admin"], "seeded admin/admin123 is neutralised")
        assertEquals("" to "TECHNICIEN", users["Eya"], "pass123 removed, role kept")
        assertEquals("" to "MANAGER", users["alice"], "sso-auth0 removed")
        val providers = d.rows("SELECT username, provider FROM users").associate { it[0] to it[1] }
        assertEquals("auth0", providers["alice"])
        assertEquals("local", providers["Eya"])
    }

    @Test
    fun migratedDatabaseKeepsPointsAndAdoptsLegacySsoRows() = runTest {
        val d = migratedV1()
        val repo = StockItRepository(StockItDatabase(d), "test")
        val alice = repo.loginSso("auth0|alice-sub", "alice", "MANAGER")
        assertEquals(120, alice.points, "legacy SSO row (no subject yet) is adopted, history kept")
        repo.logout()
        val impostor = repo.loginSso("auth0|another", "alice", "ADMIN")
        assertTrue(impostor.id != alice.id, "once bound to a subject, the row is not adopted again")
        repo.logout()
        StockItConfig.localLoginEnabled = true
        assertNull(repo.login("admin", "admin123"))
        assertNull(repo.login("Eya", "pass123"))
        assertNull(repo.login("alice", "sso-auth0"))
    }

    @Test
    fun migrationPurgesSecretsFromQueuedWebhooks() = runTest {
        val d = migratedV1()
        val repo = StockItRepository(StockItDatabase(d), "test")
        repo.loginSso("auth0|boss", "boss", "ADMIN")
        FakeHttp { 503 to "" }.install()
        repo.flushOutbox()
        val body = d.rows("SELECT body FROM outbox").single()[0]!!
        assertFalse(body.contains("old-secret"), body)
        assertTrue(body.contains("\"qty\":1"), body)
    }
}
