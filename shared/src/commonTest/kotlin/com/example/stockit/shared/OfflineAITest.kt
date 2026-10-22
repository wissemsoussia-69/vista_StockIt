package com.example.stockit.shared

import com.example.stockit.shared.ai.OfflineAI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineAITest {
    @Test
    fun invoiceExtractsPurchaseOrders() {
        val ocr = """
            Dell Technologies France
            Bon de livraison
            PO: 4521
            Latitude 5440 x5
            Purchase Order #PO-7788
            Monitor P2422H x2
        """.trimIndent()
        val n = OfflineAI.parseDeliveryNote(ocr)
        val nums = n.purchaseOrders.map { it.number }
        assertTrue("PO-4521" in nums, nums.toString())
        assertTrue("PO-7788" in nums, nums.toString())
        assertTrue(n.supplier!!.contains("Dell"))
    }

    @Test
    fun labelExtractsFields() {
        val ocr = """
            EPOS
            IMPACT 100 MS Stereo USB-C+A
            Art.-No. 1001421
            QTY: 20
            PO 3480
        """.trimIndent()
        val l = OfflineAI.parsePackageLabel(ocr)
        assertEquals(20, l.quantity)
        assertEquals("1001421", l.articleNumber)
        assertEquals("EPOS", l.brand)
        assertEquals("3480", l.poOnLabel)
        assertTrue(l.productName!!.contains("IMPACT"))
    }
}

class ProductMatcherTest {
    private val stock = listOf(
        com.example.stockit.shared.model.Product(id = 1, name = "Laptop Dell Latitude", category = "Informatique", quantity = 15),
        com.example.stockit.shared.model.Product(id = 2, name = "Souris Logitech MX", category = "Périphérique", quantity = 4),
        com.example.stockit.shared.model.Product(id = 3, name = "Ecran HP 24 pouces", category = "Informatique", quantity = 2),
    )
    private val m = com.example.stockit.shared.ai.ProductMatcher

    @Test fun accents() = assertEquals(3L, m.bestMatch("Écran HP E24", stock)?.id)
    @Test fun synonymsLaptop() = assertEquals(1L, m.bestMatch("Ordinateur portable Dell Latitude 5440", stock)?.id)
    @Test fun synonymsMonitor() = assertEquals(3L, m.bestMatch("Moniteur Samsung", stock)?.id)
    @Test fun brandModel() = assertEquals(2L, m.bestMatch("Souris Logitech MX Master 3S", stock)?.id)
    @Test fun noMatch() = assertEquals(null, m.bestMatch("Imprimante Brother", stock))
}

class AssetGroupingTest {
    private fun u(label: String, status: String = "In stock", brand: String? = null, model: String? = null, type: String = "940", serial: String? = null) =
        com.example.stockit.shared.ai.AssetGrouping.AssetUnit("SIT-" + label.hashCode(), type, label, status, brand, model, serial, null, null, null)
    private val g = com.example.stockit.shared.ai.AssetGrouping

    @Test fun lotsAreGrouped() {
        val r = g.group(listOf(u("EPOS SC 160 #001"), u("EPOS SC 160 #002"), u("EPOS SC 160 #003")))
        assertEquals(1, r.size); assertEquals(3, r[0].quantity); assertEquals("EPOS SC 160", r[0].name)
    }
    @Test fun serialSuffixRemoved() {
        val r = g.group(listOf(u("IMPACT 100 MS Stereo USB-C+A - SN 0255004491", brand = "EPOS", serial = "0255004491")))
        assertEquals("EPOS IMPACT 100 MS Stereo USB-C+A", r[0].name); assertEquals(listOf("0255004491"), r[0].serials)
    }
    @Test fun spellingVariantsMerged() {
        val r = g.group(listOf(
            u("DELL PRO14PLUS ULTRA7 265U-16G-256GSSD-WINIIPRO-3Y", brand = "Dell", type = "939"),
            u("DELL PRO 14 PLUS ULTRA7 265U-16G-256GSSD-WIN11 PRO-3Y", brand = "Dell", type = "939"),
            u("DELL PRO14PLUS ULTRA7 265U-16G-256GSSD-WIN11PRO-3Y", brand = "Dell", type = "939"),
        ))
        assertEquals(1, r.size); assertEquals(3, r[0].quantity)
    }
    @Test fun onlyInStockCounted() {
        val r = g.group(listOf(u("Mouse #001"), u("Mouse #002", status = "OUT"), u("Mouse #003", status = "Inactive")))
        assertEquals(1, r[0].quantity)
    }
    @Test fun typesKeptApart() {
        val r = g.group(listOf(u("Dell P2425H", type = "938"), u("Dell P2425H", type = "940")))
        assertEquals(2, r.size)
    }
}

class AssetGroupingSerialNameTest {
    private val g = com.example.stockit.shared.ai.AssetGrouping
    private fun u(label: String, model: String?, serial: String?) =
        com.example.stockit.shared.ai.AssetGrouping.AssetUnit("SIT-" + label, "939", label, "In stock", "Dell", model, serial, null, null, null)
    @Test fun serialNamedComputersGroupedByModel() {
        val r = g.group(listOf(
            u("6Qc9HF4", "DELL PRO14PLUS ULTRA7 265U-16G-256GSSD-WINIIPRO-3Y", "6Qc9HF4"),
            u("8FLD6J4", "DELL PRO 14 PLUS ULTRA7 265U-16G-256GSSD-WIN11 PRO-3Y", "8FLD6J4"),
            u("DELL PRO14PLUS ULTRA7 265U-16G-256GSSD-WIN11P", "DELL PRO14PLUS ULTRA7 265U-16G-256GSSD-WIN11PRO-3Y", "5BLD6J4"),
        ))
        assertEquals(1, r.size); assertEquals(3, r[0].quantity); assertEquals(3, r[0].serials.size)
    }
}
