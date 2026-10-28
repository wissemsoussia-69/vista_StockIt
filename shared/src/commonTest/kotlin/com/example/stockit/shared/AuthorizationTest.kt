package com.example.stockit.shared

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.example.stockit.shared.ai.AssetGrouping
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.network.JiraAssetsClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Threat model T-04 (client authorization), T-09 (destructive sync), T-16 (test mode, audit log). */
class AuthorizationTest {

    @BeforeTest fun setUp() = resetGlobals()
    @AfterTest fun tearDown() = resetGlobals()

    private fun SqlDriver.count(sql: String): Long =
        executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    /** Creates a product as an admin, then switches to the requested identity. */
    private suspend fun StockItRepository.seedProduct(name: String = "Souris Logitech", qty: Int = 5): Long {
        loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        val id = addProduct(name, "Périphérique", null, null, "TAG-$name", qty, 10.0, "", "", null)!!
        logout()
        return id
    }

    // ------------------------------------------------------------------ read-only USER

    @Test
    fun readOnlyUserCannotChangeStock() = runTest {
        val (repo, _) = newTestRepository()
        val id = repo.seedProduct()
        repo.loginSso("auth0|viewer", "viewer", "USER")
        val p = repo.getProduct(id)!!
        assertNull(repo.updateQuantity(p, -3), "USER is read-only")
        assertFalse(repo.recordExitToTicket(p, 1, "SD-12", null))
        assertEquals(5, repo.getProduct(id)!!.quantity)
    }

    @Test
    fun readOnlyUserCannotCreateOrEditShippingOrders() = runTest {
        val (repo, driver) = newTestRepository()
        repo.loginSso("auth0|viewer", "viewer", "USER")
        assertFalse(repo.addShippingOrder("ACME", "Laptop x1", "1 rue X", "SD-1"))
        assertEquals(0, driver.count("SELECT COUNT(*) FROM shipping_orders"))
    }

    @Test
    fun auditLogAndUserListAreRestricted() = runTest {
        val (repo, _) = newTestRepository()
        repo.seedProduct()
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        assertTrue(repo.getAuditLogs().isEmpty(), "technicians must not read everyone's audit trail")
        assertTrue(repo.getUsers().isEmpty(), "user administration is admin-only")
        repo.logout()
        repo.loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        assertTrue(repo.getAuditLogs().isNotEmpty())
        assertTrue(repo.getUsers().isNotEmpty())
    }

    @Test
    fun leaderboardIsAvailableButNeverExposesCredentials() = runTest {
        val (repo, _) = newTestRepository()
        StockItConfig.localLoginEnabled = true
        repo.provisionLocalUser("tech", "Correct-Horse-1", "TECHNICIEN")
        repo.loginSso("auth0|viewer", "viewer", "USER")
        val board = repo.getLeaderboard()
        assertEquals(setOf("tech", "viewer"), board.map { it.username }.toSet())
        assertFalse(board.toString().contains("pbkdf2"), "no password material in returned models")
        assertFalse(board.toString().contains("Correct-Horse-1"))
    }

    // ------------------------------------------------------------------ technicians keep their workflow

    @Test
    fun technicianCanAdjustStockAndServeTickets() = runTest {
        val (repo, _) = newTestRepository()
        val id = repo.seedProduct(qty = 5)
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        val p = repo.getProduct(id)!!
        assertEquals(6, repo.updateQuantity(p, 1)?.quantity)
        assertTrue(repo.recordExitToTicket(repo.getProduct(id)!!, 2, "SD-12", "nouvel arrivant"))
        assertTrue(repo.recordExitToTicket(repo.getProduct(id)!!, 1, "NO-TICKET", "Sortie libre"))
        assertEquals(3, repo.getProduct(id)!!.quantity)
    }

    @Test
    fun exitToTicketRejectsMalformedTicketKeys() = runTest {
        val (repo, _) = newTestRepository()
        val id = repo.seedProduct(qty = 5)
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        for (bad in listOf("../../rest/api/3/myself", "SD-1?x=1", "sd 12", "", "SD-")) {
            assertFalse(repo.recordExitToTicket(repo.getProduct(id)!!, 1, bad, null), bad)
        }
        assertEquals(5, repo.getProduct(id)!!.quantity)
    }

    // ------------------------------------------------------------------ anonymous

    @Test
    fun anonymousCallerCannotTriggerSideEffects() = runTest {
        val (repo, driver) = newTestRepository()
        val id = repo.seedProduct()
        val http = FakeHttp().also { it.install() }
        driver.execute(null, "INSERT INTO outbox(url, body, attempts, lastError, createdAt) VALUES ('https://n8n.test/e', '{}', 0, NULL, 0)", 0)

        assertEquals(0, http.requests.size)
        repo.flushOutbox()
        assertEquals(0, http.requests.size, "outbox replay requires a signed-in user")
        assertFalse(repo.syncFromJiraAssets().success)
        assertFalse(repo.triggerLowStockAlert(repo.getProduct(id)!!).success)
        assertNull(repo.updateQuantity(repo.getProduct(id)!!, 1))
    }

    @Test
    fun adminCannotDeleteTheirOwnAccount() = runTest {
        val (repo, _) = newTestRepository()
        val me = repo.loginSso("auth0|boss", "boss", "ADMIN")
        assertFalse(repo.deleteUser(me))
        assertTrue(repo.getUsers().any { it.id == me.id })
    }

    // ------------------------------------------------------------------ T-09 Jira Assets sync

    private fun unit(label: String, n: Int) = AssetGrouping.AssetUnit("SIT-$label-$n", "940", label, "In stock", null, null, null, null, null, null)

    private suspend fun syncedProduct(repo: StockItRepository, label: String, qty: Int): Long {
        val key = AssetGrouping.group(listOf(unit(label, 1))).single().key
        repo.loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        val id = repo.addProduct(label, "Périphérique", null, null, "JA:$key", qty, 0.0, "", "", null)!!
        repo.logout()
        return id
    }

    @Test
    fun incompleteAssetsFetchNeverZeroesStock() = runTest {
        val (repo, _) = newTestRepository()
        val id = syncedProduct(repo, "Dell Keyboard KB216", 5)
        repo.assetsFetcher = { JiraAssetsClient.FetchResult(listOf(unit("Other Mouse", 1)), null, complete = false) }
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        assertTrue(repo.syncFromJiraAssets().success)
        assertEquals(5, repo.getProduct(id)!!.quantity, "a truncated result is not proof that units disappeared")
    }

    @Test
    fun emptyAssetsFetchNeverZeroesStock() = runTest {
        val (repo, _) = newTestRepository()
        val id = syncedProduct(repo, "Dell Keyboard KB216", 5)
        repo.assetsFetcher = { JiraAssetsClient.FetchResult(emptyList(), null, complete = true) }
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        repo.syncFromJiraAssets()
        assertEquals(5, repo.getProduct(id)!!.quantity, "an empty CMDB answer (permissions, outage) must not wipe the inventory")
    }

    @Test
    fun completeAssetsFetchStillAlignsInventory() = runTest {
        val (repo, _) = newTestRepository()
        val id = syncedProduct(repo, "Dell Keyboard KB216", 5)
        repo.assetsFetcher = { JiraAssetsClient.FetchResult(listOf(unit("Other Mouse", 1), unit("Other Mouse", 2)), null, complete = true) }
        repo.loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        val r = repo.syncFromJiraAssets()
        assertTrue(r.success)
        assertEquals(0, repo.getProduct(id)!!.quantity, "Jira Assets stays the source of truth")
        assertEquals(2, repo.getStock().first { it.name == "Other Mouse" }.quantity)
        val log = repo.getAuditLogs().first { it.action == "ASSETS_SYNC" }.details.orEmpty()
        assertTrue(log.contains("Dell Keyboard KB216") && log.contains("5→0"), "before/after recorded: $log")
    }

    // ------------------------------------------------------------------ T-16 test mode & audit trail

    @Test
    fun onlyAdminsCanToggleTestModeAndItIsAudited() = runTest {
        val (repo, _) = newTestRepository()
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        assertFalse(repo.setTestMode(true))
        assertFalse(StockItConfig.testMode)
        repo.logout()
        repo.loginSso("auth0|boss", "boss", "ADMIN")
        assertTrue(repo.setTestMode(true))
        assertTrue(StockItConfig.testMode)
        assertTrue(repo.getAuditLogs().any { it.action == "TEST_MODE_ON" })
        repo.logout()
        assertFalse(StockItConfig.testMode, "test mode never outlives the admin session")
    }

    @Test
    fun auditEntriesDoNotFakeAnIpAddress() = runTest {
        val (repo, _) = newTestRepository()
        repo.seedProduct()
        repo.loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        val logs = repo.getAuditLogs()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.ipAddress == "127.0.0.1" })
    }

    @Test
    fun auditTrailIsTamperEvident() = runTest {
        val (repo, driver) = newTestRepository()
        repo.seedProduct()
        repo.loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        assertTrue(repo.verifyAuditChain())
        driver.execute(null, "UPDATE audit_logs SET details = 'rien à signaler' WHERE action = 'ADD_PRODUCT'", 0)
        assertFalse(repo.verifyAuditChain(), "editing a past entry must break the chain")
    }

    @Test
    fun auditTrailDetectsDeletedEntries() = runTest {
        val (repo, driver) = newTestRepository()
        repo.seedProduct()
        repo.loginSso("auth0|seed-admin", "seed-admin", "ADMIN")
        assertNotNull(repo.getAuditLogs().firstOrNull { it.action == "ADD_PRODUCT" })
        driver.execute(null, "DELETE FROM audit_logs WHERE action = 'ADD_PRODUCT'", 0)
        assertFalse(repo.verifyAuditChain())
    }
}
