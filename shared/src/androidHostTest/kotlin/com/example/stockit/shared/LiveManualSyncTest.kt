package com.example.stockit.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.stockit.shared.ai.AssetGrouping
import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.network.JiraAssetsClient
import com.example.stockit.shared.session.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Test RÉEL (STOCKIT_LIVE=1) : produit créé à la main → Jira, +1, renommage, suppression → Inactive. */
class LiveManualSyncTest {
    private class Mem : KeyValueStore {
        val m = mutableMapOf<String, Any?>()
        override fun getString(key: String) = m[key] as String?
        override fun putString(key: String, value: String?) { m[key] = value }
        override fun getBoolean(key: String, default: Boolean) = m[key] as Boolean? ?: default
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
        override fun clear() = m.clear()
    }

    private suspend fun jiraCount(name: String): Pair<Int, Int> {
        val units = JiraAssetsClient.fetchAll().units!!
        val mine = units.filter { AssetGrouping.stripUnitSuffix(it.label).equals(name, true) }
        return mine.count { AssetGrouping.isInStock(it.status) } to mine.count { it.status.equals("Inactive", true) }
    }

    @Test fun manualProductLifecycle() = runBlocking {
        if (System.getenv("STOCKIT_LIVE") != "1") return@runBlocking
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { StockItDatabase.Schema.create(it) }
        StockIt.setup(driver, Mem(), "test-agent")
        val repo = StockIt.repository
        repo.login("admin", "admin123")!!
        val name = "StockIT TEST SYNC"
        val name2 = "StockIT TEST SYNC RENAMED"

        val id = repo.addProduct(name, "Accessoires", null, "test", "ASSET-TEST", 2, 0.0, "", "", "test")!!
        var (inStock, _) = jiraCount(name); println("APRES CREATION : $inStock en stock dans Jira"); assertEquals(2, inStock)

        repo.syncFromJiraAssets()
        val p1 = repo.getProduct(id)!!
        println("TAG LIE : ${p1.assetTag}")
        assertTrue(p1.assetTag!!.startsWith("JA:"))
        assertEquals(1, repo.getStock().count { it.name == name }, "pas de doublon après synchro")

        repo.updateQuantity(p1, 1)
        inStock = jiraCount(name).first; println("APRES +1 : $inStock"); assertEquals(3, inStock)

        repo.syncFromJiraAssets()
        val p2 = repo.getProduct(id)!!
        repo.updateProduct(p2.withEdits(name2, p2.category, p2.description, p2.assetTag, p2.quantity, p2.unitPrice, p2.minThreshold))
        inStock = jiraCount(name2).first; println("APRES RENOMMAGE : $inStock sous le nouveau nom"); assertEquals(3, inStock)

        repo.syncFromJiraAssets()
        assertEquals(1, repo.getStock().count { it.name == name2 })
        repo.deleteProduct(repo.getStock().first { it.name == name2 })
        val (left, inactive) = jiraCount(name2); println("APRES SUPPRESSION : $left en stock, $inactive Inactive"); assertEquals(0, left); assertEquals(3, inactive)
    }
}
