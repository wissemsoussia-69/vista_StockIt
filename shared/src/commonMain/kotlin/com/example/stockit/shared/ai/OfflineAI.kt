package com.example.stockit.shared.ai

import com.example.stockit.shared.formatMoney
import com.example.stockit.shared.model.AIInsight
import com.example.stockit.shared.model.Product
import com.example.stockit.shared.tr
import com.example.stockit.shared.model.ReportData
import com.example.stockit.shared.config.StockItConfig

/**
 * Mode hors ligne (aucune clé IA) : extraction par règles du texte OCR Apple Vision,
 * et réponses / rapport générés localement à partir des données du stock.
 * Moins précis qu'un LLM, mais l'app reste entièrement utilisable.
 */
object OfflineAI {

    // ------------------------------------------------------------ factures

    private val PO_REGEX = Regex(
        """(?i)\b(?:P\.?O\.?|purchase\s*order|bon\s*de\s*commande|commande|order|BL)\s*(?:n[°o.]?|#|no\.?|number|:)?\s*[:#-]?\s*([A-Z]{0,4}-?\d{3,10})""",
    )
    private val SUPPLIER_HINTS = listOf("dell", "hp", "lenovo", "apple", "logitech", "epos", "jabra", "samsung", "microsoft", "cdw", "bechtle", "insight", "ingram")

    fun parseDeliveryNote(ocr: String): ParsedNote {
        val lines = ocr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val pos = linkedMapOf<String, String>()
        lines.forEachIndexed { i, line ->
            PO_REGEX.findAll(line).forEach { m ->
                val raw = m.groupValues[1].uppercase()
                val num = if (raw.any { it.isLetter() }) raw else "PO-$raw"
                if (num !in pos) {
                    val ctx = (lines.getOrNull(i + 1) ?: line).take(80)
                    pos[num] = ctx
                }
            }
        }
        val supplier = lines.firstOrNull { l -> SUPPLIER_HINTS.any { l.lowercase().contains(it) } }
            ?: lines.firstOrNull()
        return ParsedNote(ocr, supplier?.take(60), pos.map { POBlock(it.key, it.value) }, null)
    }

    // ------------------------------------------------------------ étiquettes

    private val QTY_REGEX = Regex("""(?i)\b(?:qty|quantity|qte|qté|quantité|pcs|stk|menge)\s*[:.]?\s*(\d{1,5})""")
    private val ART_REGEX = Regex("""(?i)\b(?:art\.?\s*-?\s*no\.?|article|art\.?nr|p/?n|part\s*(?:no|number)|sku|ref\.?)\s*[:#.]?\s*([A-Z0-9-]{4,20})""")
    private val PO_LABEL_REGEX = Regex("""(?i)\bP\.?O\.?\s*(?:n[°o.]?|#|:)?\s*[:#-]?\s*([A-Z]{0,4}-?\d{3,10})""")
    private val BRANDS = listOf("EPOS", "Jabra", "Logitech", "Dell", "HP", "Lenovo", "Apple", "Samsung", "Microsoft", "Poly", "Sennheiser", "Philips", "LG", "Asus", "Acer")

    fun parsePackageLabel(ocr: String): PackageLabel {
        val lines = ocr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val qty = PackageLabelParser.boundQuantity(QTY_REGEX.find(ocr)?.groupValues?.get(1)?.toIntOrNull() ?: 1)
        val art = ART_REGEX.find(ocr)?.groupValues?.get(1)
        val po = PO_LABEL_REGEX.find(ocr)?.groupValues?.get(1)
        val brand = BRANDS.firstOrNull { b -> Regex("""\b${Regex.escape(b)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(ocr) }
        // Nom produit : ligne la plus "descriptive" (lettres + chiffres, sans libellés techniques)
        val name = lines
            .filterNot { QTY_REGEX.containsMatchIn(it) || ART_REGEX.containsMatchIn(it) || PO_LABEL_REGEX.containsMatchIn(it) }
            .filter { it.count { c -> c.isLetter() } >= 4 && it.length in 6..60 }
            .maxByOrNull { l -> (if (brand != null && l.contains(brand, true)) 10 else 0) + l.split(' ').size }
        return PackageLabel(ocr, name, qty, art, brand, po, null, extractSerials(ocr))
    }

    private val SERIAL_REGEX = Regex(
        """(?i)\b(?:s/?n|serial\s*(?:no\.?|number|#)?|service\s*tag|n°\s*de\s*série)\s*[:#.]?\s*([A-Z0-9][A-Z0-9-]{3,29})""",
    )

    /** Numéros de série explicitement libellés (S/N, Serial, Service Tag) dans un texte OCR. */
    fun extractSerials(ocr: String?): List<String> =
        if (ocr.isNullOrBlank()) emptyList()
        else SERIAL_REGEX.findAll(ocr).mapNotNull { com.example.stockit.shared.network.AIGateway.normalizeSerial(it.groupValues[1]) }
            .distinct().toList()

    // ------------------------------------------------------------ assistant

    fun answer(question: String, products: List<Product>, report: ReportData, insights: List<AIInsight>): String {
        val q = question.lowercase()
        val low = products.filter { it.quantity in 1..it.minThreshold }
        val out = products.filter { it.quantity == 0 }
        val match = products.filter { p ->
            p.name.lowercase().split(' ').any { w -> w.length >= 4 && q.contains(w) } ||
                (p.category?.lowercase()?.let { it.length >= 4 && q.contains(it) } ?: false)
        }
        return buildString {
            append(tr("🤖 Mode hors ligne (IA non configurée) — réponse générée à partir de votre stock.\n\n", "🤖 Offline mode (AI not configured) — answer generated from your stock.\n\n"))
            when {
                match.isNotEmpty() -> {
                    match.take(8).forEach { append(tr("• ${it.name} : ${it.quantity} en stock (seuil ${it.minThreshold})\n", "• ${it.name}: ${it.quantity} in stock (threshold ${it.minThreshold})\n")) }
                }
                listOf("rupture", "manque", "épuisé", "out", "missing", "empty").any { q.contains(it) } -> {
                    if (out.isEmpty()) append(tr("Aucun article en rupture.\n", "No items out of stock.\n"))
                    else out.forEach { append(tr("• ${it.name} : en rupture\n", "• ${it.name}: out of stock\n")) }
                }
                listOf("bas", "faible", "commander", "réappro", "low", "order", "restock").any { q.contains(it) } -> {
                    if (low.isEmpty()) append(tr("Aucun article sous le seuil.\n", "No items below threshold.\n"))
                    else low.forEach { append(tr("• ${it.name} : ${it.quantity} (seuil ${it.minThreshold})\n", "• ${it.name}: ${it.quantity} (threshold ${it.minThreshold})\n")) }
                }
                listOf("valeur", "prix", "coût", "€", "total", "value", "price", "cost").any { q.contains(it) } -> {
                    append(tr("Valeur totale du stock : ${formatMoney(report.totalValue)} €\n", "Total stock value: ${formatMoney(report.totalValue)} €\n"))
                    append(tr("Quantité totale : ${report.totalQuantity} articles\n", "Total quantity: ${report.totalQuantity} items\n"))
                }
                else -> {
                    append(tr("Résumé : ${report.totalQuantity} articles, ${report.outOfStockCount} en rupture, ", "Summary: ${report.totalQuantity} items, ${report.outOfStockCount} out of stock, "))
                    append(tr("${report.lowStockCount} en stock bas, valeur ${formatMoney(report.totalValue)} €.\n", "${report.lowStockCount} low stock, value ${formatMoney(report.totalValue)} €.\n"))
                    insights.take(3).forEach { append("• ${it.title} — ${it.description}\n") }
                }
            }
        }.trim()
    }

    // ------------------------------------------------------------ rapport

    fun monthlyReport(products: List<Product>, report: ReportData, insights: List<AIInsight>): String = buildString {
        append(tr("Synthèse du stock IT Vistaprint\n\n", "Vistaprint IT stock summary\n\n"))
        append(tr("L'inventaire compte ${report.totalQuantity} articles pour une valeur de ${formatMoney(report.totalValue)} €. ", "The inventory holds ${report.totalQuantity} items worth ${formatMoney(report.totalValue)} €. "))
        append(tr("${report.outOfStockCount} référence(s) en rupture et ${report.lowStockCount} en stock bas.\n\n", "${report.outOfStockCount} item(s) out of stock and ${report.lowStockCount} low on stock.\n\n"))
        if (report.categories.isNotEmpty()) {
            append(tr("Répartition par catégorie :\n", "Breakdown by category:\n"))
            report.categories.sortedByDescending { it.total }.forEach { append("• ${it.category ?: tr("Autre", "Other")} : ${it.total}\n") }
            append("\n")
        }
        val critical = products.filter { it.quantity <= it.minThreshold }.sortedBy { it.quantity }
        if (critical.isNotEmpty()) {
            append(tr("Alertes critiques :\n", "Critical alerts:\n"))
            critical.take(8).forEach { append(tr("• ${it.name} : ${it.quantity} (seuil ${it.minThreshold})\n", "• ${it.name}: ${it.quantity} (threshold ${it.minThreshold})\n")) }
            append("\n")
        }
        append(tr("Recommandations :\n", "Recommendations:\n"))
        val recos = insights.filter { it.priority == "HIGH" || it.priority == "MEDIUM" }.take(5)
        if (recos.isEmpty()) append(tr("• Aucune action urgente ; maintenir le suivi hebdomadaire.\n", "• No urgent action; keep the weekly follow-up.\n"))
        else recos.forEach { append("• ${it.title} — ${it.description}\n") }
        if (critical.isNotEmpty()) append(tr("• Lancer une commande fournisseur pour les références sous le seuil.\n", "• Place a supplier order for items below threshold.\n"))
        append(tr("\n(Rapport généré localement — mode hors ligne.)", "\n(Report generated locally — offline mode.)"))
    }
}
