package com.example.stockit.shared

import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.network.JiraAssetsClient
import com.example.stockit.shared.network.JiraAssetsClient.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraAssetsTest {

    @Test
    fun kindForRoutesToTheRightObjectType() {
        assertEquals(Kind.MONITOR, JiraAssetsClient.kindFor("Écran Dell P2425H"))
        assertEquals(Kind.MONITOR, JiraAssetsClient.kindFor("DELL monitor 27"))
        assertEquals(Kind.COMPUTER, JiraAssetsClient.kindFor("Ordinateur portable Dell Latitude"))
        assertEquals(Kind.COMPUTER, JiraAssetsClient.kindFor("Dell Pro Max 16"))
        assertEquals(Kind.COMPUTER, JiraAssetsClient.kindFor("MacBook Pro 14"))
        assertEquals(Kind.PERIPHERAL, JiraAssetsClient.kindFor("Souris Logitech MX Master 3S"))
        assertEquals(Kind.PERIPHERAL, JiraAssetsClient.kindFor("EPOS IMPACT 100 MS stéréo USB-C+A"))
        // « pc » ne doit pas matcher l'intérieur d'un mot
        assertEquals(Kind.PERIPHERAL, JiraAssetsClient.kindFor("Clavier Apcon"))
    }

    @Test
    fun assetTypeLabelsMatchExistingSchemaValues() {
        assertEquals("Monitor", JiraAssetsClient.assetTypeLabel(Kind.MONITOR, "Écran"))
        assertEquals("Peripheral", JiraAssetsClient.assetTypeLabel(Kind.PERIPHERAL, "Souris"))
        assertEquals("Laptop", JiraAssetsClient.assetTypeLabel(Kind.COMPUTER, "Dell Latitude 5440"))
        assertEquals("Desktop", JiraAssetsClient.assetTypeLabel(Kind.COMPUTER, "Dell OptiPlex"))
    }

    @Test
    fun objectLabelsFollowSchemaConvention() {
        assertEquals("DELL P2425H - SN 46C9HF4", JiraAssetsClient.objectLabel("DELL P2425H", 0, 1, "46C9HF4"))
        assertEquals("Souris", JiraAssetsClient.objectLabel("Souris", 0, 1, null))
        assertEquals("EPOS IMPACT 100 #001", JiraAssetsClient.objectLabel("EPOS IMPACT 100", 0, 20, null))
        assertEquals("EPOS IMPACT 100 - SN AB12", JiraAssetsClient.objectLabel("EPOS IMPACT 100", 19, 20, "AB12"))
        // une série par unité, jamais réutilisée
        assertEquals("S2", JiraAssetsClient.serialFor(listOf("S1", "S2"), 1))
        assertEquals(null, JiraAssetsClient.serialFor(listOf("S1", "S2"), 2))
    }

    @Test
    fun aqlIsScopedAndEscaped() {
        StockItConfig.jiraAssetsSchemaId = "247"
        val q = JiraAssetsClient.aqlInStock("940", "Casque \"EPOS\"  100")
        assertTrue(q.startsWith("objectSchemaId = 247 AND objectTypeId = 940"))
        assertTrue(q.contains("\"Asset Status\" = \"In stock\""))
        assertTrue(q.contains("Name like \"Casque EPOS 100\""), q)
        assertTrue(q.endsWith("ORDER BY Created ASC"))
    }

    @Test
    fun typeIdFallsBackToPeripherals() {
        StockItConfig.jiraAssetsTypePeripherals = "940"
        StockItConfig.jiraAssetsTypeMonitors = ""
        assertEquals("940", JiraAssetsClient.typeIdFor(Kind.MONITOR))
        StockItConfig.jiraAssetsTypeMonitors = "938"
        assertEquals("938", JiraAssetsClient.typeIdFor(Kind.MONITOR))
    }
}
