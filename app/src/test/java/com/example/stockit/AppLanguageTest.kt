package com.example.stockit

import com.example.stockit.util.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Les traductions de l'app Android sont lues dans le même fichier que l'app iOS. */
class AppLanguageTest {

    private fun iosStrings(): Map<String, String> {
        val f = listOf("../iosApp/StockIT/Resources/en.lproj/Localizable.strings", "iosApp/StockIT/Resources/en.lproj/Localizable.strings")
            .map { File(it) }.first { it.exists() }
        return AppLanguage.parseStrings(f.readText())
    }

    @Test
    fun parsesIosTranslations() {
        val t = iosStrings()
        assertTrue("table iOS vide", t.size > 400)
        assertEquals("Inventory", t["Inventaire"])
        assertEquals("Synced with Jira Assets", t["Synchronisé avec Jira Assets"])
        assertEquals("Last sync: %@", t["Dernière synchronisation : %@"])
        assertEquals("✅ Inventory synced: %@ models, %@ units", t["✅ Inventaire synchronisé : %@ modèles, %@ unités"])
    }

    @Test
    fun unescapesNewlines() {
        val t = iosStrings()
        assertEquals("\nTotal checked out: %@", t["\nTotal sorti : %@"])
    }

    @Test
    fun parsesCommentsAndEscapes() {
        val t = AppLanguage.parseStrings("/* c */\n\"a \\\"b\\\"\" = \"x\";\n// l\n\"k\" = \"v\\n2\";")
        assertEquals("x", t["a \"b\""])
        assertEquals("v\n2", t["k"])
    }
}
