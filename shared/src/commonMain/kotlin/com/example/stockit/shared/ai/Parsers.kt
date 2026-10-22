package com.example.stockit.shared.ai

import com.example.stockit.shared.tr
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.JiraTicket
import com.example.stockit.shared.network.AIGateway
import com.example.stockit.shared.network.Http
import com.example.stockit.shared.network.int
import com.example.stockit.shared.network.str
import com.example.stockit.shared.network.stripCodeFence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** [confidence] : certitude de Claude 0-100 (-1 si non fournie). */
data class Assignment(val ticketId: String, val qty: Int, val reason: String, val confidence: Int = -1)
data class AssignmentResult(val assignments: List<Assignment>?, val error: String?)

/** Seuils de confiance IA (sous LOW : confirmation manuelle obligatoire). */
object Confidence {
    const val LOW = 60
    const val HIGH = 85

    /** « high » / « medium » / « low » / « unknown » — utilisé par l'UI pour la couleur et le verrou. */
    fun level(c: Int): String = when {
        c < 0 -> "unknown"
        c >= HIGH -> "high"
        c >= LOW -> "medium"
        else -> "low"
    }

    /** true si la proposition IA doit être confirmée explicitement par l'utilisateur. */
    fun requiresManualCheck(c: Int): Boolean = c in 0 until LOW
}

/**
 * Répartition d'une sortie de stock entre tickets Jira par Claude (Cimpress Gateway).
 * Portage de util/TicketMatcher.java.
 */
object TicketMatcher {

    @Throws(Throwable::class)
    suspend fun assign(equipmentName: String, quantity: Int, tickets: List<JiraTicket>): AssignmentResult {
        if (tickets.isEmpty()) return AssignmentResult(emptyList(), "no_open_tickets")
        if (quantity <= 0) return AssignmentResult(emptyList(), "invalid_quantity")
        if (!StockItConfig.isAIConfigured) {
            return AssignmentResult(null, tr("IA non configurée : choisissez le ticket dans la liste ou tapez son ID.",
                "AI not configured: pick the ticket from the list or type its ID."))
        }
        val r = AIGateway.cimpressChat(buildPrompt(equipmentName, quantity, tickets), maxTokens = 1000)
        if (r.error != null) return AssignmentResult(null, r.error)
        return AssignmentResult(parseAssignments(r.text, quantity), null)
    }

    internal fun buildPrompt(item: String, qty: Int, tickets: List<JiraTicket>): String = buildString {
        append("Contexte : StockIT - sortie d'equipement IT.\n")
        append("Equipement scanne : ").append(item).append("\n")
        append("Quantite a sortir : ").append(qty).append("\n\n")
        append("Tickets Jira ouverts :\n")
        for (t in tickets) {
            append("---\n")
            append("ID       : ").append(t.key).append("\n")
            append("Status   : ").append(t.status ?: "?").append("\n")
            append("Priority : ").append(t.priority ?: "?").append("\n")
            append("Due date : ").append(t.dueDate ?: "?").append("\n")
            append("Summary  : ").append(t.summary ?: "?").append("\n")
            val d = t.description
            if (!d.isNullOrEmpty()) {
                append("Description : ").append(if (d.length > 400) d.take(400) + "..." else d).append("\n")
            }
        }
        append("\nAnalyse les descriptions et repartis la quantite entre les tickets qui semblent")
        append(" avoir besoin de cet equipement.\n")
        append("Ordre de priorite :\n")
        append(" 1. Priority Jira DESC (Highest / J1 > Lowest / J5)\n")
        append(" 2. Due date ASC (le plus proche d'abord)\n")
        append(" 3. Sinon ordre d'apparition\n\n")
        append("Renvoie UNIQUEMENT ce JSON strict :\n")
        append("[\n")
        append("  { \"ticketId\": \"ETXTUN-42\", \"qty\": 3, \"reason\": \"1-2 phrases pourquoi ce ticket\", \"confidence\": 90 }\n")
        append("]\n")
        append("Regles :\n")
        append(" - N'assigne QUE la quantite reellement necessaire (ne remplis pas pour remplir).\n")
        append(" - Somme totale des qty <= ").append(qty).append(" ; le reste sera gere manuellement.\n")
        append(" - confidence = entier 0-100 : 90+ si le ticket demande explicitement cet equipement,\n")
        append("   50-70 si c'est une deduction (ex: nouvel arrivant), < 50 si c'est incertain.\n")
        append(" - ticketId doit etre EXACTEMENT un des ID listes ci-dessus.\n")
        append(" - Si AUCUN ticket ne correspond, renvoie []. Ne devine pas.\n")
        append(" - Reponds uniquement le JSON, pas de markdown, pas de texte avant/apres.\n")
    }

    internal fun parseAssignments(llm: String?, maxQty: Int): List<Assignment> {
        if (llm == null) return emptyList()
        val arr = runCatching { Http.json.parseToJsonElement(stripCodeFence(llm)) as? JsonArray }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<Assignment>()
        var running = 0
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val id = o.str("ticketId") ?: continue
            var q = o.int("qty", 0)
            if (q <= 0) continue
            if (running + q > maxQty) q = maxQty - running
            if (q <= 0) break
            out += Assignment(id, q, o.str("reason") ?: "", o.int("confidence", -1).coerceAtMost(100))
            running += q
            if (running >= maxQty) break
        }
        return out
    }
}

// =============================================================================

data class POBlock(val number: String, val summary: String)

data class ParsedNote(
    val rawOcr: String?,
    val supplier: String?,
    val purchaseOrders: List<POBlock>,
    val error: String?,
)

/**
 * Facture / bon de livraison : texte OCR (MLKit Android, Apple Vision iOS) → JSON via Claude.
 * Portage de util/DeliveryNoteParser.java (l'OCR reste côté plateforme).
 */
object DeliveryNoteParser {

    @Throws(Throwable::class)
    suspend fun parseOcr(ocrText: String?): ParsedNote {
        if (ocrText.isNullOrBlank()) return ParsedNote(ocrText, null, emptyList(), tr("OCR vide — le texte n'a pas été détecté", "Empty OCR — no text was detected"))
        val prompt =
            "Voici le texte OCR d'une facture ou bon de livraison. " +
                "Elle peut contenir PLUSIEURS numéros de PO (Purchase Order, BL, commande...). " +
                "Extrais UNIQUEMENT un JSON strict avec cette structure exacte :\n" +
                "{\n" +
                "  \"supplier\": \"nom du fournisseur/expéditeur\",\n" +
                "  \"purchaseOrders\": [\n" +
                "    { \"number\": \"PO-1234\", \"description\": \"1 phrase courte résumant les articles associés à ce PO\" }\n" +
                "  ]\n" +
                "}\n" +
                "Règles strictes :\n" +
                "- Détecte TOUS les numéros de PO/BL/commande présents.\n" +
                "- Pour CHAQUE PO, résume en 1 phrase (max 15 mots) le ou les articles rattachés.\n" +
                "- Si aucun PO trouvé, renvoie purchaseOrders: [].\n" +
                "- Réponds UNIQUEMENT le JSON, rien avant, rien après, pas de balise markdown.\n\n" +
                "Texte OCR :\n" + ocrText
        if (!StockItConfig.isAIConfigured) return OfflineAI.parseDeliveryNote(ocrText)
        val r = AIGateway.cimpressChat(prompt, maxTokens = 1200)
        if (r.error != null) {
            val off = OfflineAI.parseDeliveryNote(ocrText)
            return if (off.purchaseOrders.isNotEmpty()) off else ParsedNote(ocrText, null, emptyList(), r.error)
        }
        val llm = r.text ?: return ParsedNote(ocrText, null, emptyList(), "llm_empty")
        val clean = stripCodeFence(llm)
        return runCatching {
            val obj = Http.json.parseToJsonElement(clean).jsonObject
            val pos = (obj["purchaseOrders"] as? JsonArray)?.mapNotNull { e ->
                val po = e as? JsonObject ?: return@mapNotNull null
                val n = po.str("number") ?: return@mapNotNull null
                POBlock(n, po.str("description") ?: "")
            } ?: emptyList()
            ParsedNote(ocrText, obj.str("supplier"), pos, null)
        }.getOrElse { ParsedNote(ocrText, null, emptyList(), "json_parse_fail — raw=${clean.take(120)}") }
    }

    /** "PO-12" → 12 (chiffres uniquement). */
    fun extractIntFromPoNumber(s: String?): Long? {
        val d = s?.filter { it.isDigit() } ?: return null
        return d.takeIf { it.isNotEmpty() }?.toLongOrNull()
    }
}

// =============================================================================

data class PackageLabel(
    val rawOcr: String?,
    val productName: String?,
    val quantity: Int,
    val articleNumber: String?,
    val brand: String?,
    val poOnLabel: String?,
    val error: String?,
    /** Numéros de série lus sur l'étiquette (S/N, Serial, Service Tag) — un par unité si listés. */
    val serials: List<String> = emptyList(),
)

/** Étiquette de carton : OCR → JSON via Claude. Portage de util/PackageLabelParser.java. */
object PackageLabelParser {

    @Throws(Throwable::class)
    suspend fun parseOcr(ocrText: String?): PackageLabel {
        fun err(e: String) = PackageLabel(ocrText, null, 1, null, null, null, e)
        if (ocrText.isNullOrBlank()) return err(tr("OCR vide — texte non détecté sur l'étiquette", "Empty OCR — no text detected on the label"))
        val prompt =
            "Voici le texte OCR d'une ETIQUETTE DE CARTON d'expedition d'un produit informatique. " +
                "Extrais UNIQUEMENT ce JSON strict :\n" +
                "{\n" +
                "  \"productName\":   \"Nom exact et complet du produit (ex: IMPACT 100 MS Stereo USB-C+A)\",\n" +
                "  \"quantity\":       20,\n" +
                "  \"articleNumber\": \"Numero d'article (Art.-No.)\",\n" +
                "  \"brand\":          \"Marque (ex: EPOS)\",\n" +
                "  \"poOnLabel\":      \"Numero PO imprime sur l'etiquette (ex: 3480 ou PO-3480), ou null si absent\",\n" +
                "  \"serials\":        [\"Numeros de serie (S/N, Serial No, Service Tag) lus sur l'etiquette\"]\n" +
                "}\n" +
                "Regles strictes :\n" +
                "- quantity est un ENTIER (regarde QTY / QUANTITY / Qte). Par defaut 1 si absent.\n" +
                "- productName = designation complete, PAS le nom de la marque seule.\n" +
                "- serials : UNIQUEMENT les valeurs explicitement precedees de S/N, SN, Serial, Service Tag. " +
                "PAS l'EAN/UPC, PAS l'Art.-No., PAS le PO. [] si aucun.\n" +
                "- Ne devine PAS : si un champ est absent, mets une chaine vide ou null.\n" +
                "- Reponds UNIQUEMENT le JSON, rien avant, rien apres, pas de markdown.\n\n" +
                "Texte OCR :\n" + ocrText
        if (!StockItConfig.isAIConfigured) return OfflineAI.parsePackageLabel(ocrText)
        val r = AIGateway.cimpressChat(prompt, maxTokens = 600)
        if (r.error != null) return OfflineAI.parsePackageLabel(ocrText)
        val llm = r.text ?: return err("llm_empty")
        val clean = stripCodeFence(llm)
        return runCatching {
            val o = Http.json.parseToJsonElement(clean).jsonObject
            val q = o.int("quantity", 1).let { if (it <= 0) 1 else it }
            val serials = (o["serials"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(AIGateway::normalizeSerial) }
                .distinct()
                .ifEmpty { OfflineAI.extractSerials(ocrText) }
            PackageLabel(ocrText, o.str("productName"), q, o.str("articleNumber"), o.str("brand"), o.str("poOnLabel"), null, serials)
        }.getOrElse { err("json_parse_fail — raw=${clean.take(120)}") }
    }

    /** true si les deux PO (facture / étiquette) sont incompatibles. */
    fun poMismatch(expected: String?, onLabel: String?): Boolean {
        if (expected.isNullOrBlank() || onLabel.isNullOrBlank()) return false
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val a = norm(expected)
        val b = norm(onLabel)
        return !(a == b || a.contains(b) || b.contains(a))
    }
}
