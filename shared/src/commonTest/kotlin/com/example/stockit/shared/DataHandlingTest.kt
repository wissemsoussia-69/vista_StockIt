package com.example.stockit.shared

import com.example.stockit.shared.ai.OfflineAI
import com.example.stockit.shared.ai.PackageLabelParser
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Threat model T-14 (CSV injection), T-15 (barcodes), T-22 (unbounded inputs). */
class DataHandlingTest {

    @BeforeTest fun setUp() = resetGlobals()
    @AfterTest fun tearDown() = resetGlobals()

    private suspend fun adminRepo(): StockItRepository {
        val (repo, _) = newTestRepository()
        repo.loginSso("auth0|boss", "boss", "ADMIN")
        return repo
    }

    @Test
    fun csvExportNeutralisesFormulasAndQuotesFields() = runTest {
        val repo = adminRepo()
        repo.addProduct("=HYPERLINK(\"http://x\",\"y\")", "Accessoires", null, null, "T1", 3, 12.5, "", "", null)
        repo.addProduct("Souris, sans fil", "Accessoires", null, null, "T2", 1, 0.0, "", "", null)
        repo.addProduct("@SUM(A1)", "Accessoires", null, null, "T3", 1, 0.0, "", "", null)
        val lines = repo.buildStockCsv().trim().lines()
        assertEquals("ID,Nom,Quantite,Prix", lines[0])
        val byName = lines.drop(1)
        assertTrue(byName.contains("1,\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\",3,12.5"), byName.toString())
        assertTrue(byName.contains("2,\"Souris, sans fil\",1,0.0"), byName.toString())
        assertTrue(byName.contains("3,\"'@SUM(A1)\",1,0.0"), byName.toString())
    }

    @Test
    fun searchTreatsWildcardsLiterally() = runTest {
        val repo = adminRepo()
        repo.addProduct("Câble 100% cuivre", "Accessoires", null, null, "T1", 1, 0.0, "", "", null)
        repo.addProduct("Souris", "Accessoires", null, null, "T2", 1, 0.0, "", "", null)
        repo.addProduct("Clé_USB", "Accessoires", null, null, "T3", 1, 0.0, "", "", null)
        assertEquals(listOf("Câble 100% cuivre"), repo.searchProducts("%").map { it.name })
        assertEquals(listOf("Clé_USB"), repo.searchProducts("_").map { it.name })
        assertEquals(listOf("Souris"), repo.searchProducts("ouri").map { it.name })
    }

    @Test
    fun quantityChangesAreBoundedAndNeverOverflow() = runTest {
        val repo = adminRepo()
        val id = repo.addProduct("Écran Dell", "Écran", null, null, "T1", 5, 0.0, "", "", null)!!
        assertNull(repo.updateQuantity(repo.getProduct(id)!!, Int.MAX_VALUE))
        assertNull(repo.updateQuantity(repo.getProduct(id)!!, Int.MIN_VALUE))
        assertNull(repo.updateQuantity(repo.getProduct(id)!!, 0))
        assertEquals(5, repo.getProduct(id)!!.quantity, "rejected deltas leave the stock untouched")
        assertEquals(15, repo.updateQuantity(repo.getProduct(id)!!, 10)?.quantity)
        assertEquals(0, repo.updateQuantity(repo.getProduct(id)!!, -20)?.quantity, "floor at 0 is kept")
    }

    @Test
    fun lowStockAlertsAreRateLimitedPerProduct() = runTest {
        val repo = adminRepo()
        val a = repo.addProduct("Souris", "Accessoires", null, null, "T1", 1, 0.0, "", "", null)!!
        val b = repo.addProduct("Clavier", "Accessoires", null, null, "T2", 1, 0.0, "", "", null)!!
        assertTrue(repo.triggerLowStockAlert(repo.getProduct(a)!!).detail != "rate_limited")
        assertEquals("rate_limited", repo.triggerLowStockAlert(repo.getProduct(a)!!).detail)
        assertTrue(repo.triggerLowStockAlert(repo.getProduct(b)!!).detail != "rate_limited", "other products are not affected")
    }

    @Test
    fun batchScanRejectsImplausibleBarcodes() = runTest {
        val repo = adminRepo()
        assertNull(repo.addOrIncrementByCode("<!channel>"))
        assertNull(repo.addOrIncrementByCode("A".repeat(300)))
        assertNull(repo.addOrIncrementByCode("line1\nline2"))
        assertNull(repo.addOrIncrementByCode("  "))
        assertEquals("created", repo.addOrIncrementByCode("4044155246834"))
        assertEquals("updated", repo.addOrIncrementByCode("4044155246834"))
        assertEquals("created", repo.addOrIncrementByCode("SN-ABC/123.45"))
        assertEquals(2, repo.getStock().size)
    }

    @Test
    fun offlineLabelQuantityIsBounded() {
        val l = OfflineAI.parsePackageLabel("EPOS IMPACT 100\nQTY: 99999")
        assertTrue(l.quantity in 1..PackageLabelParser.MAX_QUANTITY, "quantity=${l.quantity}")
        assertEquals(20, OfflineAI.parsePackageLabel("EPOS IMPACT 100\nQTY: 20").quantity)
    }
}
