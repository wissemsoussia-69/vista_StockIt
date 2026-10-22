package com.example.stockit.shared

import com.example.stockit.shared.ai.AssetGrouping
import com.example.stockit.shared.network.JiraAssetsClient
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/** Aperçu réel (lecture seule) : exécuté seulement si STOCKIT_LIVE=1. */
class LiveAssetsPreview {
    @Test fun preview() = runBlocking {
        if (System.getenv("STOCKIT_LIVE") != "1") return@runBlocking
        val r = JiraAssetsClient.fetchAll()
        println("ERREUR=" + r.error)
        val units = r.units ?: return@runBlocking
        println("UNITES=" + units.size + " IN_STOCK=" + units.count { AssetGrouping.isInStock(it.status) })
        AssetGrouping.group(units).forEach { println("GROUPE ${it.quantity} x [${it.typeId}] ${it.name} | marque=${it.brand} | séries=${it.serials.size}") }
    }
}
