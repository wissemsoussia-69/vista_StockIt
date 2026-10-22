package com.example.stockit.shared.ai

import com.example.stockit.shared.model.Product

/**
 * Rapprochement d'un nom détecté par Claude ("Écran Dell P2422H") avec les produits
 * en stock ("Ecran HP 24 pouces") : insensible aux accents / à la casse, avec synonymes
 * FR/EN par type d'équipement.
 */
object ProductMatcher {

    private val SYNONYMS: List<Set<String>> = listOf(
        setOf("ecran", "moniteur", "monitor", "display", "screen"),
        setOf("ordinateur portable", "portable", "laptop", "notebook", "latitude", "thinkpad", "macbook", "elitebook"),
        setOf("pc de bureau", "desktop", "tour", "optiplex", "ordinateur fixe"),
        setOf("souris", "mouse"),
        setOf("clavier", "keyboard"),
        setOf("casque", "headset", "headphones", "ecouteurs", "micro-casque"),
        setOf("station d'accueil", "dock", "docking", "station d accueil"),
        setOf("webcam", "camera"),
        setOf("telephone", "phone", "smartphone", "iphone"),
        setOf("tablette", "tablet", "ipad"),
        setOf("cable", "câble", "hdmi", "usb-c", "displayport"),
        setOf("chargeur", "charger", "alimentation", "adaptateur secteur"),
        setOf("imprimante", "printer"),
    )

    fun normalize(s: String?): String {
        if (s == null) return ""
        val map = mapOf(
            'à' to 'a', 'â' to 'a', 'ä' to 'a', 'á' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
            'î' to 'i', 'ï' to 'i', 'í' to 'i', 'ô' to 'o', 'ö' to 'o', 'ó' to 'o', 'ù' to 'u', 'û' to 'u',
            'ü' to 'u', 'ú' to 'u', 'ç' to 'c', 'ñ' to 'n', '’' to '\'',
        )
        return s.lowercase().map { map[it] ?: it }.joinToString("")
            .replace(Regex("""\(offline\)"""), "")
            .replace(Regex("""[^a-z0-9' -]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
    }

    private fun words(s: String) = normalize(s).split(' ').filter { it.length >= 2 }.toSet()

    /** Groupe de synonymes (type d'équipement) d'un texte, ou null. */
    private fun typeGroup(text: String): Int? {
        val n = " ${normalize(text)} "
        return SYNONYMS.indexOfFirst { g -> g.any { n.contains(" ${normalize(it)} ") || n.startsWith(" ${normalize(it)}") } }
            .takeIf { it >= 0 }
    }

    /** Score de ressemblance (0 = aucune). */
    fun score(detected: String, p: Product): Int {
        val d = normalize(detected)
        val name = normalize(p.name)
        if (d.isEmpty() || name.isEmpty()) return 0
        if (d == name) return 1000
        var s = 0
        if (name.contains(d) || d.contains(name)) s += 300
        val dw = words(detected)
        val pw = words(p.name) + words(p.brand ?: "") + words(p.articleNumber ?: "") + words(p.category ?: "")
        // mots communs (marque, modèle…) : forte valeur s'ils contiennent des chiffres (référence)
        dw.intersect(pw).forEach { w -> s += if (w.any { it.isDigit() }) 120 else 40 }
        val tg = typeGroup(detected)
        if (tg != null && (tg == typeGroup(p.name) || tg == typeGroup(p.category ?: ""))) s += 100
        return s
    }

    /** Produits candidats triés par pertinence (score > 0). */
    fun candidates(detected: String, stock: List<Product>, onlyInStock: Boolean = false): List<Product> =
        stock.asSequence()
            .filter { !onlyInStock || it.quantity > 0 }
            .map { it to score(detected, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .toList()

    /** Meilleur produit si la correspondance est claire, sinon null. */
    fun bestMatch(detected: String, stock: List<Product>, onlyInStock: Boolean = false): Product? {
        val ranked = stock.filter { !onlyInStock || it.quantity > 0 }.map { it to score(detected, it) }.sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        return if (top.second >= 100) top.first else null
    }

    /** Catégorie de stock déduite du type détecté (Informatique / Périphérique / Accessoires). */
    fun categoryFor(detected: String): String = when (typeGroup(detected)) {
        0, 1, 2 -> "Informatique"
        3, 4, 5, 7 -> "Périphérique"
        else -> "Accessoires"
    }

    /** true si [text] (ex. résumé d'un ticket Jira) mentionne le même type d'équipement que [productName]. */
    fun mentionsSameType(text: String, productName: String): Boolean {
        val g = typeGroup(productName) ?: return false
        val n = " ${normalize(text)} "
        return SYNONYMS[g].any { n.contains(" ${normalize(it)} ") || n.contains(" ${normalize(it)}s ") }
    }
}
