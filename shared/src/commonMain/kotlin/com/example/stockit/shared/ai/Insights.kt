package com.example.stockit.shared.ai

import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.AnalyzedTicket
import com.example.stockit.shared.tr
import com.example.stockit.shared.model.AuditLog
import com.example.stockit.shared.model.Product
import com.example.stockit.shared.model.PurchaseOrder
import com.example.stockit.shared.model.Supplier
import com.example.stockit.shared.network.Http
import com.example.stockit.shared.network.int
import com.example.stockit.shared.network.str
import com.example.stockit.shared.network.stripCodeFence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// =============================================================================
//  1) QUALITÉ DE L'IA — calculée à partir des traces réelles de l'app
// =============================================================================

/** Indicateurs de qualité IA (affichés dans « Qualité de l'IA »). Pourcentages : -1 = pas de données. */
data class AIQuality(
    /** Répartition tickets : suggestions de Claude réellement appliquées / suggestions faites. */
    val ticketSuggestions: Int,
    val ticketAccepted: Int,
    val ticketAcceptanceRate: Int,
    /** Précision des quantités : livré / suggéré, plafonné à 100 % par ticket, moyenne. */
    val quantityPrecision: Int,
    /** Affectations choisies à la main (hors IA) — utile pour voir si l'IA est contournée. */
    val manualAssignments: Int,
    /** Reconnaissance d'objets : noms de Claude conservés tels quels / scans confirmés. */
    val visionScans: Int,
    val visionKept: Int,
    val visionAccuracy: Int,
    /** Confiance moyenne annoncée par Claude sur les scans. */
    val avgConfidence: Int,
    /** Scans à faible confiance (< 60 %) et combien étaient malgré tout corrects. */
    val lowConfidenceScans: Int,
    val lowConfidenceKept: Int,
    /** Numéros de série lus automatiquement. */
    val serialsRead: Int,
    /** Score global pondéré (0-100) ou -1. */
    val globalScore: Int,
)

object AIQualityCalculator {

    /** Préfixe des entrées d'audit écrites à chaque confirmation de scan. */
    const val AUDIT_VISION = "AI_VISION_FEEDBACK"

    /**
     * Détail d'audit : « kept=1;conf=85;serial=1;detected=…;final=… ».
     * kept = le nom final est celui de Claude (identique, ou l'un contient l'autre : « Souris Logitech MX »
     * rattaché au produit « Souris Logitech MX Master 3S »).
     */
    fun visionAuditDetail(detected: String?, final: String, confidence: Int, serialRead: Boolean): String {
        val d = ProductMatcher.normalize(detected)
        val f = ProductMatcher.normalize(final)
        val kept = d.isNotEmpty() && f.isNotEmpty() && (d == f || d.contains(f) || f.contains(d))
        fun clean(s: String?) = (s ?: "").replace(";", ",").take(80)
        return "kept=${if (kept) 1 else 0};conf=$confidence;serial=${if (serialRead) 1 else 0};" +
            "detected=${clean(detected)};final=${clean(final)}"
    }

    private fun field(detail: String?, key: String): String? =
        detail?.split(';')?.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    fun compute(tickets: List<AnalyzedTicket>, audit: List<AuditLog>): AIQuality {
        // ---- tickets : une ligne « manuelle » = reason « Choix manuel » ET suggested == delivered dès la création
        val manual = tickets.filter { (it.reason ?: "").startsWith("Choix manuel") || (it.reason ?: "") == "Sortie libre" }
        val ai = tickets - manual.toSet()
        val accepted = ai.filter { it.deliveredQty > 0 }
        val precision = accepted.filter { it.suggestedQty > 0 }
            .map { minOf(it.deliveredQty, it.suggestedQty).toDouble() / max(it.deliveredQty, it.suggestedQty) }
        // ---- vision
        val scans = audit.filter { it.action == AUDIT_VISION }
        val kept = scans.count { field(it.details, "kept") == "1" }
        val confs = scans.mapNotNull { field(it.details, "conf")?.toIntOrNull()?.takeIf { c -> c >= 0 } }
        val low = scans.filter { (field(it.details, "conf")?.toIntOrNull() ?: -1) in 0 until Confidence.LOW }
        val serials = scans.count { field(it.details, "serial") == "1" }

        fun pct(a: Int, b: Int) = if (b == 0) -1 else (a * 100.0 / b).roundToInt()
        val acceptance = pct(accepted.size, ai.size)
        val qPrecision = if (precision.isEmpty()) -1 else (precision.average() * 100).roundToInt()
        val vision = pct(kept, scans.size)
        val parts = listOf(acceptance to 0.4, qPrecision to 0.3, vision to 0.3).filter { it.first >= 0 }
        val global = if (parts.isEmpty()) -1
        else (parts.sumOf { it.first * it.second } / parts.sumOf { it.second }).roundToInt()

        return AIQuality(
            ticketSuggestions = ai.size, ticketAccepted = accepted.size, ticketAcceptanceRate = acceptance,
            quantityPrecision = qPrecision, manualAssignments = manual.size,
            visionScans = scans.size, visionKept = kept, visionAccuracy = vision,
            avgConfidence = if (confs.isEmpty()) -1 else confs.average().roundToInt(),
            lowConfidenceScans = low.size, lowConfidenceKept = low.count { field(it.details, "kept") == "1" },
            serialsRead = serials, globalScore = global,
        )
    }
}

// =============================================================================
//  2) PRÉVISION DES BESOINS — consommation réelle + demande Jira en attente
// =============================================================================

/** Prévision pour un produit. `daysLeft` = -1 si aucune consommation (pas de rupture prévue). */
data class Forecast(
    val productId: Long,
    val productName: String,
    val stock: Int,
    val minThreshold: Int,
    /** Sorties par jour (moyenne pondérée sur la fenêtre d'historique). */
    val dailyUsage: Double,
    /** Unités demandées par des tickets Jira ouverts (estimation) mais pas encore livrées. */
    val pendingDemand: Int,
    val daysLeft: Int,
    /** Quantité conseillée pour tenir l'horizon + délai fournisseur + seuil de sécurité. */
    val suggestedOrder: Int,
    /** CRITICAL (≤ délai fournisseur) / WARNING (≤ 2× délai ou sous seuil) / OK. */
    val risk: String,
    /** Explication locale (chiffres) ; remplacée/complétée par Claude si disponible. */
    var rationale: String,
)

/** Mouvement réduit pour le calcul (évite de dépendre du format de date texte). */
data class UsagePoint(val productId: Long, val quantity: Int, val atMs: Long)

object DemandForecaster {

    const val HISTORY_DAYS = 60
    const val HORIZON_DAYS = 30
    const val DEFAULT_LEAD_TIME = 7
    private const val DAY_MS = 86_400_000L

    /**
     * Consommation journalière : moyenne des sorties sur [HISTORY_DAYS], les 14 derniers jours
     * comptant double (tendance récente). Si l'historique est plus court, on divise par sa durée réelle.
     */
    fun dailyUsage(outs: List<UsagePoint>, nowMs: Long): Double {
        val window = outs.filter { it.atMs in (nowMs - HISTORY_DAYS * DAY_MS)..nowMs }
        if (window.isEmpty()) return 0.0
        val first = window.minOf { it.atMs }
        val spanDays = max(7.0, (nowMs - first).toDouble() / DAY_MS)
        val recentCut = nowMs - 14 * DAY_MS
        val weighted = window.sumOf { if (it.atMs >= recentCut) it.quantity * 2.0 else it.quantity.toDouble() }
        val weightDays = spanDays + minOf(14.0, spanDays)
        return weighted / weightDays
    }

    fun forecast(
        products: List<Product>,
        outs: List<UsagePoint>,
        pendingDemand: Map<Long, Int>,
        leadTimes: Map<Long, Int>,
        nowMs: Long,
    ): List<Forecast> = products.map { p ->
        val usage = dailyUsage(outs.filter { it.productId == p.id }, nowMs)
        val pending = pendingDemand[p.id] ?: 0
        val lead = leadTimes[p.id] ?: DEFAULT_LEAD_TIME
        val available = p.quantity - pending
        val daysLeft = when {
            available <= 0 -> 0
            usage <= 0.0 -> -1
            else -> (available / usage).toInt()
        }
        val need = ceil(usage * (HORIZON_DAYS + lead)).toInt() + p.minThreshold + pending - p.quantity
        val suggested = max(0, need)
        val risk = when {
            available <= 0 || (daysLeft in 0..lead) -> "CRITICAL"
            (daysLeft in 0..(2 * lead)) || p.quantity <= p.minThreshold -> "WARNING"
            else -> "OK"
        }
        val why = buildString {
            append("Stock ${p.quantity}")
            if (pending > 0) append(tr(", $pending déjà demandé(s) dans Jira", ", $pending already requested in Jira"))
            if (usage > 0) append(tr(", consommation ≈ ${(usage * 7 * 10).roundToInt() / 10.0}/semaine", ", usage ≈ ${(usage * 7 * 10).roundToInt() / 10.0}/week"))
            else append(tr(", aucune sortie sur $HISTORY_DAYS j", ", no outgoing in $HISTORY_DAYS days"))
            if (daysLeft >= 0) append(tr(" → rupture dans ~$daysLeft j", " → out of stock in ~$daysLeft days")) else append(tr(" → pas de rupture prévue", " → no stockout expected"))
            append(tr(" (délai fournisseur $lead j).", " (supplier lead time $lead days)."))
        }
        Forecast(p.id, p.name, p.quantity, p.minThreshold, usage, pending, daysLeft, suggested, risk, why)
    }.sortedWith(compareBy<Forecast>({ riskRank(it.risk) }, { if (it.daysLeft < 0) Int.MAX_VALUE else it.daysLeft }))

    private fun riskRank(r: String) = when (r) { "CRITICAL" -> 0; "WARNING" -> 1; else -> 2 }

    /** Prompt Claude : commenter / ajuster les prévisions (il ne voit que des agrégats, aucune donnée personnelle). */
    fun buildPrompt(items: List<Forecast>, openTicketSummaries: List<String>): String = buildString {
        append("Tu es le planificateur de stock IT de Vistaprint.\n")
        append("Voici des prévisions calculées à partir des sorties réelles (horizon $HORIZON_DAYS jours) :\n")
        items.take(25).forEach {
            append("- id=${it.productId} | ${it.productName} | stock ${it.stock} | seuil ${it.minThreshold} | ")
            append("conso/jour ${(it.dailyUsage * 100).roundToInt() / 100.0} | demande Jira ${it.pendingDemand} | ")
            append("rupture ${if (it.daysLeft < 0) "aucune" else "J+" + it.daysLeft} | à commander ${it.suggestedOrder} | ${it.risk}\n")
        }
        if (openTicketSummaries.isNotEmpty()) {
            append("\nRésumés des tickets Jira ouverts (pour détecter une demande à venir : onboarding, projet…) :\n")
            openTicketSummaries.take(30).forEach { append("- ").append(it.take(140)).append("\n") }
        }
        append("\nPour CHAQUE produit qui mérite une action, renvoie UNIQUEMENT ce JSON strict :\n")
        append(if (StockItConfig.isEnglish) "[{\"productId\": 12, \"orderQty\": 15, \"risk\": \"CRITICAL\", \"comment\": \"1 sentence in English\"}]\n" else "[{\"productId\": 12, \"orderQty\": 15, \"risk\": \"CRITICAL\", \"comment\": \"1 phrase en français\"}]\n")
        append("Règles : garde orderQty proche du calcul sauf si les tickets justifient plus ; ")
        append("risk parmi CRITICAL, WARNING, OK ; n'invente pas de productId ; [] si rien à signaler. Pas de markdown.")
    }

    /** Applique la réponse de Claude (ajustements bornés : 0 ≤ orderQty ≤ 3× le calcul + 10). */
    fun applyClaude(items: List<Forecast>, llm: String?): List<Forecast> {
        if (llm.isNullOrBlank()) return items
        val arr = runCatching { Http.json.parseToJsonElement(stripCodeFence(llm)) as? JsonArray }.getOrNull() ?: return items
        val byId = items.associateBy { it.productId }.toMutableMap()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val id = o.int("productId", -1).toLong()
            val f = byId[id] ?: continue
            val qty = o.int("orderQty", f.suggestedOrder).coerceIn(0, f.suggestedOrder * 3 + 10)
            val risk = o.str("risk")?.uppercase()?.takeIf { it in setOf("CRITICAL", "WARNING", "OK") } ?: f.risk
            val comment = o.str("comment")
            byId[id] = f.copy(suggestedOrder = qty, risk = risk,
                rationale = if (comment != null) "🧠 $comment\n${f.rationale}" else f.rationale)
        }
        return items.map { byId[it.productId] ?: it }
            .sortedWith(compareBy<Forecast>({ riskRank(it.risk) }, { if (it.daysLeft < 0) Int.MAX_VALUE else it.daysLeft }))
    }
}

// =============================================================================
//  3) BROUILLON DE COMMANDE — fournisseur habituel, quantité, justification
// =============================================================================

data class OrderDraft(
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val supplier: String?,
    val leadTimeDays: Int,
    val unitPrice: Double,
    val estimatedTotal: Double,
    val justification: String,
    /** true si Claude a rédigé la justification (sinon texte local). */
    val byAI: Boolean,
)

object OrderDrafter {

    /** Fournisseur habituel : le plus fréquent dans les commandes du produit, sinon le « receivedFrom » du produit. */
    fun usualSupplier(product: Product, orders: List<PurchaseOrder>, suppliers: List<Supplier>): Supplier? {
        val n = ProductMatcher.normalize(product.name)
        val fromOrders = orders.filter { ProductMatcher.normalize(it.productName) == n && !it.supplier.isNullOrBlank() }
            .groupingBy { it.supplier!!.trim() }.eachCount().maxByOrNull { it.value }?.key
        val name = fromOrders ?: product.receivedFrom?.takeIf { it.isNotBlank() }
        if (name != null) {
            suppliers.firstOrNull { ProductMatcher.normalize(it.name) == ProductMatcher.normalize(name) }?.let { return it }
            suppliers.firstOrNull { ProductMatcher.normalize(it.name).contains(ProductMatcher.normalize(name)) ||
                ProductMatcher.normalize(name).contains(ProductMatcher.normalize(it.name)) }?.let { return it }
            return Supplier(name = name, email = null, phone = null, address = null, leadTime = DemandForecaster.DEFAULT_LEAD_TIME, discount = 0.0)
        }
        // Sinon : fournisseur dont le nom apparaît dans le produit (ex « Dell »), puis délai le plus court.
        return suppliers.firstOrNull { s -> s.name.split(' ').any { w -> w.length >= 3 && n.contains(ProductMatcher.normalize(w)) } }
    }

    fun localDraft(f: Forecast, product: Product, supplier: Supplier?): OrderDraft {
        val qty = max(f.suggestedOrder, max(1, product.minThreshold - product.quantity + 1))
        val price = product.unitPrice * (1 - (supplier?.discount ?: 0.0) / 100.0)
        return OrderDraft(
            f.productId, f.productName, qty, supplier?.name, supplier?.leadTime ?: DemandForecaster.DEFAULT_LEAD_TIME,
            price, (price * qty * 100).roundToInt() / 100.0, f.rationale, byAI = false,
        )
    }

    fun buildPrompt(d: OrderDraft, f: Forecast): String =
        "Rédige la justification d'une commande de matériel IT pour Vistaprint, " +
            (if (StockItConfig.isEnglish) "IN ENGLISH" else "en français") + ", 2 phrases max, ton professionnel.\n" +
            "Produit : ${d.productName}\nQuantité : ${d.quantity}\nFournisseur : ${d.supplier ?: "à définir"} (délai ${d.leadTimeDays} j)\n" +
            "Stock actuel : ${f.stock} (seuil ${f.minThreshold}), demande Jira en attente : ${f.pendingDemand}, " +
            "consommation ≈ ${(f.dailyUsage * 7 * 10).roundToInt() / 10.0}/semaine, rupture prévue : " +
            (if (f.daysLeft < 0) "aucune" else "dans ${f.daysLeft} jours") + ".\n" +
            "Réponds uniquement par le texte de la justification, sans markdown."
}
