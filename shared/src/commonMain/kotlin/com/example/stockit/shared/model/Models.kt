package com.example.stockit.shared.model

import com.example.stockit.shared.platform.parseDateToEpoch
import kotlinx.serialization.Serializable

/*
 * StockIT — modèles métier partagés (miroirs des POJO Android du package
 * com.example.stockit.model). Classes immuables : utiliser copy() pour modifier
 * (côté Swift : `p.doCopy(quantity: 3, ...)`).
 */

data class Product(
    val id: Long = 0,
    val name: String,
    val category: String? = null,
    val categoryId: Long? = null,
    val description: String? = null,
    val assetTag: String? = null,
    val quantity: Int = 0,
    val unitPrice: Double = 0.0,
    val manufacturingDate: String? = null,
    val expirationDate: String? = null,
    val minThreshold: Int = 5,
    // Lien PO (facture)
    val poNumber: String? = null,
    val poDescription: String? = null,
    val receivedFrom: String? = null,
    // Étiquette carton
    val articleNumber: String? = null,
    val brand: String? = null,
    val packagePoNumber: String? = null,
) {
    val isLowStock: Boolean get() = quantity <= minThreshold

    /** Emoji de catégorie (même logique que ProductAdapter Android). */
    val categoryEmoji: String
        get() {
            val c = (category ?: "").lowercase()
            return when {
                "computer" in c || "laptop" in c || "ordinateur" in c -> "💻"
                "keyboard" in c || "clavier" in c -> "⌨️"
                "screen" in c || "monitor" in c || "écran" in c -> "🖥️"
                "mouse" in c || "souris" in c -> "🖱️"
                "cable" in c || "câble" in c || "connectique" in c -> "🔌"
                else -> "📦"
            }
        }

    /** Kit anti-oubli (HDMI + alim) requis pour un écran. */
    val requiresKit: Boolean get() = KitRules.requiresKit(name)

    /** Copie éditée (helper Swift : évite `doCopy` à 17 paramètres). */
    fun withEdits(
        name: String,
        category: String?,
        desc: String?,
        assetTag: String?,
        quantity: Int,
        unitPrice: Double,
        minThreshold: Int,
    ): Product = copy(
        name = name, category = category, description = desc, assetTag = assetTag,
        quantity = quantity, unitPrice = unitPrice, minThreshold = minThreshold,
    )
}

object KitRules {
    fun requiresKit(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        return "écran" in n || "ecran" in n || "monitor" in n || "moniteur" in n
    }
}

data class User(
    val id: Long = 0,
    val username: String,
    val password: String,
    val role: String,
    val points: Int = 0,
    val level: Int = 1,
    val badges: String = "",
) {
    val isAdmin: Boolean get() = role == "ADMIN"
    val canEditStock: Boolean get() = role == "ADMIN" || role == "MANAGER"
}

data class StockMovement(
    val id: Long = 0,
    val productId: Long,
    val productName: String?,
    val type: String, // "IN" | "OUT"
    val quantity: Int,
    val date: String?,
    val reason: String?,
    val userName: String?,
    val comment: String?,
    val ticketId: String? = null,
    val assignmentReason: String? = null,
)

data class ProductCategory(
    val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val labelColor: String? = null,
) {
    fun withEdits(name: String, labelColor: String?): ProductCategory = copy(name = name, labelColor = labelColor)
}

data class AuditLog(
    val id: Long = 0,
    val userId: String?,
    val action: String,
    val details: String?,
    val timestamp: Long,
    val ipAddress: String?,
    val device: String?,
)

data class Supplier(
    val id: Long = 0,
    val name: String,
    val email: String?,
    val phone: String?,
    val address: String?,
    val leadTime: Int,
    val discount: Double,
) {
    fun withEdits(name: String, email: String?, phone: String?, address: String?, leadTime: Int, discount: Double): Supplier =
        copy(name = name, email = email, phone = phone, address = address, leadTime = leadTime, discount = discount)
}

data class PurchaseOrder(
    val id: Long = 0,
    val productName: String,
    val quantity: Int,
    val supplier: String?,
    val date: String?,
    val status: String, // "En attente" | "Reçu"
) {
    fun withStatus(status: String): PurchaseOrder = copy(status = status)
}

data class ShippingOrder(
    val id: Long = 0,
    val customerName: String,
    val itemsSummary: String?,
    val shippingAddress: String?,
    val trackingNumber: String?,
    val ticketNumber: String?,
    val status: String, // PENDING / PREPARING / SHIPPED / DELIVERED
) {
    fun withStatus(status: String, trackingNumber: String?): ShippingOrder =
        copy(status = status, trackingNumber = trackingNumber)
}

@Serializable
data class Claim(
    val id: Long = 0,
    val subject: String,
    val description: String? = null,
    val senderName: String? = null,
    val priority: String? = null, // BASSE / MOYENNE / HAUTE
    val status: String? = "OUVERT", // OUVERT / EN_COURS / RESOLU
    val timestamp: Long = 0,
)

@Serializable
data class ChatMessage(
    val senderId: String? = null,
    val senderName: String? = null,
    val message: String? = null,
    val timestamp: Long = 0,
)

data class Quest(
    val id: Long = 0,
    val title: String,
    val description: String?,
    val goalCount: Int,
    val currentCount: Int = 0,
    val pointReward: Int,
    val badgeReward: String?,
    val isCompleted: Boolean = false,
)

data class AnalyzedTicket(
    val id: Long = 0,
    val ticketId: String,
    val equipmentKey: String,
    val equipmentName: String?,
    val suggestedQty: Int,
    val deliveredQty: Int,
    val reason: String?,
    val fulfilled: Boolean,
    val analyzedAt: Long,
    val fulfilledAt: Long,
    val jiraUpdated: String?,
    val ticketSummary: String?,
)

enum class InsightType { PREDICTION, RECOMMENDATION, ANOMALY, PRICE_SUGGESTION, RISK }

data class AIInsight(
    val title: String,
    val description: String,
    val type: InsightType,
    val priority: String, // HIGH / MEDIUM / LOW
)

data class CategoryCount(val category: String?, val total: Int)

data class ReportData(
    val totalQuantity: Int,
    val lowStockCount: Int,
    val totalValue: Double,
    val outOfStockCount: Int,
    val categories: List<CategoryCount>,
)

/** Ticket Jira (REST v3), description aplatie depuis l'ADF. */
data class JiraTicket(
    val key: String,
    val summary: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val dueDate: String? = null, // "yyyy-MM-dd"
    val description: String? = null,
    val assignee: String? = null,
    val reporter: String? = null,
) {
    fun toShort(): String = buildString {
        append(key)
        status?.let { append(" · ").append(it) }
        priority?.let { append(" · ").append(it) }
        dueDate?.let { append(" · due ").append(it) }
    }

    /** Poids numérique de la priorité (plus grand = plus urgent). */
    fun priorityWeight(): Int {
        val p = priority?.lowercase() ?: return 0
        return when {
            "highest" in p || "j1" in p || "critique" in p -> 5
            "high" in p || "j2" in p || "haute" in p -> 4
            "medium" in p || "j3" in p || "moyenne" in p -> 3
            "low" in p || "j4" in p || "basse" in p -> 2
            "lowest" in p || "j5" in p -> 1
            else -> 3
        }
    }

    /** Échéance en epoch ms (dueDate "yyyy-MM-dd", fuseau local), null si absente/invalide. */
    fun dueEpochMs(): Long? = dueDate?.let { parseDateToEpoch(it, "yyyy-MM-dd") }

    /**
     * Libellé du chip SLA (TicketAdapter Android) : "SLA -2j" en retard,
     * "SLA 3h" si < 24h, sinon "Due 5j". null si pas d'échéance.
     */
    fun slaLabel(now: Long): String? {
        val due = dueEpochMs() ?: return null
        val delta = due - now
        val days = delta / 86_400_000L
        val hours = delta / 3_600_000L
        return when {
            delta < 0 -> "SLA ${days}j"
            hours < 24 -> "SLA ${maxOf(hours, 1L)}h"
            else -> "Due ${days}j"
        }
    }

    fun isOverdue(now: Long): Boolean = dueEpochMs()?.let { it < now } ?: false

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return listOf(key, summary, description, priority).any { it?.lowercase()?.contains(q) == true }
    }
}

/** Résultat générique pour les appels réseau exposés à Swift. */
data class OpResult(val success: Boolean, val detail: String?)
