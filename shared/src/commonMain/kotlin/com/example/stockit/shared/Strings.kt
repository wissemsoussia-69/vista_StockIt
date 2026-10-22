package com.example.stockit.shared

import com.example.stockit.shared.config.StockItConfig

/** Choisit le texte selon la langue de l'app (Paramètres). */
internal fun tr(fr: String, en: String): String = if (StockItConfig.isEnglish) en else fr

/**
 * Libellés FR utilisés par la logique métier (copie des ressources Android
 * res/values/strings.xml : mov_*, audit_*, ai_insight_*, cat_*, …).
 */
object Strings {
    const val MOV_NEW_PRODUCT = "Nouvel article ajouté"
    const val MOV_MANUAL_ADJ = "Ajustement manuel"
    const val MOV_DELETED = "Article supprimé"

    const val USER_SYSTEM = "Système"
    const val USER_GUEST = "Invité"
    const val USER_ME = "Moi"
    const val CLAIMS_ANONYMOUS = "Anonyme"

    const val PURCHASE_PENDING = "En attente"
    const val PURCHASE_RECEIVED = "Reçu"
    const val SHIP_PENDING = "EN ATTENTE"

    const val CAT_ACCESSORIES = "Accessoires"
    const val CAT_COMPUTER = "Ordinateur"
    const val CAT_DESKTOP = "Desktop"
    const val CAT_LAPTOP = "Laptop"
    const val CAT_MONITOR = "Écran"

    val AI_SYSTEM_ROLE: String get() = tr(
        "Tu es un assistant expert en gestion de stock IT pour Vista Services. Réponds en français.",
        "You are an expert IT inventory assistant for Vista Services. Answer in English.")
    val AI_WELCOME: String get() = tr(
        "Bonjour ! Je suis votre assistant StockIT. Comment puis-je vous aider aujourd'hui ?",
        "Hello! I'm your StockIT assistant. How can I help you today?")
    val AI_ERROR: String get() = tr("Désolé, une erreur est survenue avec l'IA.", "Sorry, an error occurred with the AI.")
    val AI_NOT_CONFIGURED: String get() = tr(
        "⚠️ IA non configurée : renseignez CIMPRESS_GATEWAY_KEY (passerelle Cimpress) ou CLAUDE_API_KEY (Claude direct) dans .env.sh puis recompilez.",
        "⚠️ AI not configured: set CIMPRESS_GATEWAY_KEY (Cimpress gateway) or CLAUDE_API_KEY (Claude direct) in .env.sh, then rebuild.")
    /** Consigne ajoutée aux prompts Claude dont la réponse est affichée à l'utilisateur. */
    val AI_ANSWER_LANGUAGE: String get() = tr("Réponds en français.", "Answer in English.")

    fun auditCatAdded(n: String) = "Catégorie ajoutée: $n"
    fun auditCatUpdated(n: String) = "Catégorie mise à jour: $n"
    fun auditCatDeleted(n: String) = "Catégorie supprimée: $n"
    fun auditProdAdded(n: String, q: Int) = "Produit ajouté: $n (Qty: $q)"
    fun auditProdUpdated(n: String) = "Produit mis à jour: $n"
    fun auditProdDeleted(n: String) = "Produit supprimé: $n"
    fun auditQtyAdjusted(n: String, d: Int) = "Quantité ajustée pour $n: $d"
    fun auditPurchaseCreated(n: String, q: Int) = "Commande créée: $n (Qty: $q)"
    fun auditSupAdded(n: String) = "Fournisseur ajouté: $n"
    fun auditSupUpdated(n: String) = "Fournisseur mis à jour: $n"
    fun auditSupDeleted(n: String) = "Fournisseur supprimé: $n"
    fun auditShipCreated(n: String) = "Expédition créée pour: $n"
    fun auditShipUpdated(n: String) = "Expédition mise à jour: $n"
    fun auditShipDeleted(n: String) = "Expédition supprimée: $n"

    // --- Textes affichés à l'écran (traduits) ---
    fun insightRecommendation(n: String) = tr("Réapprovisionnement suggéré: $n", "Restock suggested: $n")
    fun insightRecommendationDesc(q: Int) = tr(
        "La demande prédite est de $q unités. Stock actuel insuffisant.",
        "Predicted demand is $q units. Current stock is insufficient.")
    val INSIGHT_ANOMALY: String get() = tr("Anomalie détectée", "Anomaly detected")
    fun insightAnomalyDesc(q: Int, n: String) = tr("Mouvement inhabituel de $q unités pour $n", "Unusual movement of $q units for $n")
    fun insightDeadStock(n: String) = tr("Stock dormant: $n", "Dead stock: $n")
    val INSIGHT_DEAD_STOCK_DESC: String get() = tr(
        "Aucun mouvement détecté. Suggérer une réduction de 15% pour vider le stock.",
        "No movement detected. Suggest a 15% discount to clear the stock.")
    val INSIGHT_RISK: String get() = tr("Risque d'obsolescence", "Obsolescence risk")
    fun insightRiskDesc(n: String, d: String) = tr("$n approche de sa date de sortie ($d)", "$n is nearing its end date ($d)")
    val INSIGHT_DONE: String get() = tr("Analyse terminée", "Analysis complete")
    val INSIGHT_NO_ALERTS: String get() = tr("Aucune alerte IA pour le moment.", "No AI alerts for now.")
}
