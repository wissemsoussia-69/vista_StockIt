package com.example.stockit.shared

import com.example.stockit.shared.ai.AIQualityCalculator
import com.example.stockit.shared.ai.Confidence
import com.example.stockit.shared.ai.DemandForecaster
import com.example.stockit.shared.ai.OfflineAI
import com.example.stockit.shared.ai.OrderDrafter
import com.example.stockit.shared.ai.ProductMatcher
import com.example.stockit.shared.ai.TicketMatcher
import com.example.stockit.shared.ai.UsagePoint
import com.example.stockit.shared.model.AnalyzedTicket
import com.example.stockit.shared.model.AuditLog
import com.example.stockit.shared.model.Product
import com.example.stockit.shared.model.PurchaseOrder
import com.example.stockit.shared.model.Supplier
import com.example.stockit.shared.network.AIGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AIFeaturesTest {

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------ confiance + série (vision)

    @Test
    fun visionParsesConfidenceAndSerial() {
        val v = AIGateway.parseVision("""{"type":"Écran","brand":"Dell","model":"P2425H","name":"Écran Dell P2425H","serial":"S/N: 46c9 hf4","confidence":92}""")
        assertEquals("Écran Dell P2425H", v.name)
        assertEquals(92, v.confidence)
        assertEquals("46C9HF4", v.serial)
        val none = AIGateway.parseVision("""{"name":"Souris","serial":null,"confidence":40}""")
        assertNull(none.serial)
        assertEquals(40, none.confidence)
        assertEquals(-1, AIGateway.parseVision("Souris Logitech").confidence)
    }

    @Test
    fun serialNormalizationRejectsPlaceholders() {
        assertEquals("MXK53ZM", AIGateway.normalizeSerial("SN MXK53ZM"))
        assertEquals("H77FHF4", AIGateway.normalizeSerial("Service Tag: H77FHF4"))
        assertNull(AIGateway.normalizeSerial("N/A"))
        assertNull(AIGateway.normalizeSerial("unknown"))
        assertNull(AIGateway.normalizeSerial("12"))
    }

    @Test
    fun offlineSerialExtraction() {
        val ocr = "EPOS IMPACT 100\nArt.-No.: 1001421\nS/N: A1B2C3D4\nSerial No. ZX-99887\nEAN 4044155246834"
        assertEquals(listOf("A1B2C3D4", "ZX-99887"), OfflineAI.extractSerials(ocr))
        assertEquals(listOf("A1B2C3D4", "ZX-99887"), OfflineAI.parsePackageLabel(ocr).serials)
    }

    @Test
    fun confidenceThresholds() {
        assertEquals("high", Confidence.level(90))
        assertEquals("medium", Confidence.level(70))
        assertEquals("low", Confidence.level(30))
        assertEquals("unknown", Confidence.level(-1))
        assertTrue(Confidence.requiresManualCheck(59))
        assertTrue(!Confidence.requiresManualCheck(60))
        assertTrue(!Confidence.requiresManualCheck(-1)) // inconnue : pas de verrou (ancienne réponse)
    }

    @Test
    fun ticketAssignmentsCarryConfidence() {
        val a = TicketMatcher.parseAssignments("""[{"ticketId":"SD-1","qty":2,"reason":"x","confidence":95},{"ticketId":"SD-2","qty":1,"reason":"y"}]""", 5)
        assertEquals(95, a[0].confidence)
        assertEquals(-1, a[1].confidence)
    }

    // ------------------------------------------------------------ qualité IA

    @Test
    fun qualityFromTicketsAndScans() {
        fun t(id: String, sug: Int, del: Int, reason: String = "IA") =
            AnalyzedTicket(0, id, "souris", "Souris", sug, del, reason, del >= sug, 0, 0, null, null)
        val tickets = listOf(t("SD-1", 2, 2), t("SD-2", 4, 2), t("SD-3", 1, 0), t("SD-4", 1, 1, "Choix manuel"))
        fun a(d: String) = AuditLog(0, "u", AIQualityCalculator.AUDIT_VISION, d, 0, null, null)
        val audit = listOf(
            a(AIQualityCalculator.visionAuditDetail("Souris Logitech", "souris logitech", 90, true)),
            a(AIQualityCalculator.visionAuditDetail("Clavier", "Casque EPOS", 40, false)),
        )
        val q = AIQualityCalculator.compute(tickets, audit)
        assertEquals(3, q.ticketSuggestions)
        assertEquals(2, q.ticketAccepted)
        assertEquals(67, q.ticketAcceptanceRate)
        assertEquals(75, q.quantityPrecision) // (1.0 + 0.5) / 2
        assertEquals(1, q.manualAssignments)
        assertEquals(2, q.visionScans)
        assertEquals(50, q.visionAccuracy)
        assertEquals(65, q.avgConfidence)
        assertEquals(1, q.lowConfidenceScans)
        assertEquals(0, q.lowConfidenceKept)
        assertEquals(1, q.serialsRead)
        assertTrue(q.globalScore in 60..70)
    }

    @Test
    fun qualityWithoutDataIsUnknown() {
        val q = AIQualityCalculator.compute(emptyList(), emptyList())
        assertEquals(-1, q.globalScore)
        assertEquals(-1, q.visionAccuracy)
    }

    // ------------------------------------------------------------ prévision

    @Test
    fun forecastPredictsStockout() {
        val p = Product(id = 1, name = "Souris Logitech", quantity = 10, minThreshold = 3)
        // 2 souris par jour pendant 14 jours
        val outs = (1..14).map { UsagePoint(1, 2, now - it * day) }
        val f = DemandForecaster.forecast(listOf(p), outs, emptyMap(), mapOf(1L to 7), now).single()
        assertTrue(f.dailyUsage in 1.5..2.5, "usage=${f.dailyUsage}")
        assertTrue(f.daysLeft in 3..7, "daysLeft=${f.daysLeft}")
        assertEquals("CRITICAL", f.risk)
        assertTrue(f.suggestedOrder >= 60, "order=${f.suggestedOrder}")
    }

    @Test
    fun forecastWithoutUsageAndPendingDemand() {
        val p = Product(id = 2, name = "Écran Dell", quantity = 3, minThreshold = 2)
        val idle = DemandForecaster.forecast(listOf(p), emptyList(), emptyMap(), emptyMap(), now).single()
        assertEquals(-1, idle.daysLeft)
        assertEquals("OK", idle.risk)
        assertEquals(0, idle.suggestedOrder)
        val busy = DemandForecaster.forecast(listOf(p), emptyList(), mapOf(2L to 5), emptyMap(), now).single()
        assertEquals(0, busy.daysLeft)
        assertEquals("CRITICAL", busy.risk)
        assertEquals(4, busy.suggestedOrder) // 0 conso + seuil 2 + 5 demandés - 3 en stock
    }

    @Test
    fun claudeAdjustmentsAreBounded() {
        val p = Product(id = 3, name = "Casque", quantity = 1, minThreshold = 2)
        val base = DemandForecaster.forecast(listOf(p), emptyList(), emptyMap(), emptyMap(), now)
        val adj = DemandForecaster.applyClaude(base, """[{"productId":3,"orderQty":9999,"risk":"critical","comment":"Onboarding de 5 personnes"},{"productId":99,"orderQty":5}]""")
        val f = adj.single()
        assertEquals(base.single().suggestedOrder * 3 + 10, f.suggestedOrder)
        assertEquals("CRITICAL", f.risk)
        assertTrue(f.rationale.startsWith("🧠 Onboarding"))
    }

    // ------------------------------------------------------------ brouillon de commande

    @Test
    fun usualSupplierFromPastOrders() {
        val p = Product(id = 1, name = "Souris Logitech MX", quantity = 0, unitPrice = 50.0)
        val sup = listOf(Supplier(1, "Dell France", null, null, null, 5, 10.0), Supplier(2, "Logitech Pro", null, null, null, 7, 15.0))
        val orders = listOf(
            PurchaseOrder(1, "Souris Logitech MX", 5, "Logitech Pro", null, "Reçu"),
            PurchaseOrder(2, "souris logitech mx", 5, "Logitech Pro", null, "Reçu"),
            PurchaseOrder(3, "Souris Logitech MX", 5, "Dell France", null, "Reçu"),
        )
        assertEquals("Logitech Pro", OrderDrafter.usualSupplier(p, orders, sup)?.name)
        // sans historique : fournisseur dont le nom apparaît dans le produit
        assertEquals("Logitech Pro", OrderDrafter.usualSupplier(p, emptyList(), sup)?.name)
        val f = DemandForecaster.forecast(listOf(p), emptyList(), mapOf(1L to 3), emptyMap(), now).single()
        val d = OrderDrafter.localDraft(f, p, sup[1])
        assertEquals(42.5, d.unitPrice)
        assertEquals(d.quantity * 42.5, d.estimatedTotal)
    }

    @Test
    fun ticketMentionsEquipmentType() {
        assertTrue(ProductMatcher.mentionsSameType("Besoin de 2 souris pour nouvel arrivant", "Souris Logitech MX"))
        assertTrue(ProductMatcher.mentionsSameType("New hire needs a monitor", "Écran Dell P2425H"))
        assertTrue(!ProductMatcher.mentionsSameType("Problème VPN", "Souris Logitech MX"))
    }
}
