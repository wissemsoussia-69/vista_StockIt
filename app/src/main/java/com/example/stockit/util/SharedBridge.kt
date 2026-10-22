package com.example.stockit.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.stockit.model.AppDatabase
import com.example.stockit.shared.AndroidStockIt
import com.example.stockit.shared.ai.AIQuality
import com.example.stockit.shared.ai.AIQualityCalculator
import com.example.stockit.shared.ai.Assignment
import com.example.stockit.shared.ai.DemandForecaster
import com.example.stockit.shared.ai.Forecast
import com.example.stockit.shared.ai.OrderDraft
import com.example.stockit.shared.ai.OrderDrafter
import com.example.stockit.shared.ai.ProductMatcher
import com.example.stockit.shared.ai.TicketMatcher
import com.example.stockit.shared.ai.UsagePoint
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.OpResult
import com.example.stockit.shared.network.AIGateway
import com.example.stockit.shared.network.JiraAssetsClient
import com.example.stockit.shared.network.JiraClient
import com.example.stockit.shared.network.SlackNotifier as SharedSlack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import com.example.stockit.model.AnalyzedTicket as RoomTicket
import com.example.stockit.model.AuditLog as RoomAudit
import com.example.stockit.model.JiraTicket as RoomJiraTicket
import com.example.stockit.model.Product as RoomProduct
import com.example.stockit.shared.model.AnalyzedTicket as SharedTicket
import com.example.stockit.shared.model.AuditLog as SharedAudit
import com.example.stockit.shared.model.JiraTicket as SharedJiraTicket
import com.example.stockit.shared.model.Product as SharedProduct
import com.example.stockit.shared.model.PurchaseOrder as SharedPO
import com.example.stockit.shared.model.Supplier as SharedSupplier

/**
 * StockIT — Passerelle Java ↔ module KMP `:shared` (même logique métier que l'app iOS).
 *
 * La base Room reste la source de vérité côté Android : on convertit les entités Room
 * vers les modèles partagés pour réutiliser les algorithmes iOS (rapprochement produit,
 * vision Claude avec confiance + n° de série, prévisions, brouillons de commande,
 * qualité IA, Jira Assets, répartition des tickets avec confiance).
 *
 * Tous les callbacks sont invoqués sur le thread principal.
 */
object SharedBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private const val PREFS = "stockit_settings"
    private const val KEY_TEST_MODE = "test_mode"

    fun interface VisionCallback { fun onResult(name: String?, confidence: Int, serial: String?, error: String?) }
    fun interface OpCallback { fun onResult(success: Boolean, detail: String?) }
    fun interface ForecastCallback { fun onResult(items: List<Forecast>) }
    fun interface DraftCallback { fun onResult(draft: OrderDraft) }
    fun interface AssignCallback { fun onResult(assignments: List<Assignment>?, error: String?) }
    fun interface QualityCallback { fun onResult(q: AIQuality) }

    // ------------------------------------------------------------------ init / config

    @JvmStatic
    fun init(context: Context) {
        AndroidStockIt.setup(context)
        StockItConfig.testMode = isTestMode(context)
        StockItConfig.language = AppLanguage.current(context)
        AssetsSync.init(context)
    }

    @JvmStatic
    fun isTestMode(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEST_MODE, false)

    @JvmStatic
    fun setTestMode(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEST_MODE, on).apply()
        StockItConfig.testMode = on
    }

    /** Rôle appliqué à un employé SSO sans groupe StockIT (STOCKIT_SSO_DEFAULT_ROLE, comme iOS). */
    @JvmStatic
    fun ssoDefaultRole(): String = StockItConfig.ssoDefaultRole

    @JvmStatic fun isAIConfigured() = StockItConfig.isAIConfigured
    @JvmStatic fun isCimpressConfigured() = StockItConfig.isCimpressConfigured
    @JvmStatic fun isClaudeConfigured() = StockItConfig.isClaudeConfigured
    @JvmStatic fun isJiraConfigured() = StockItConfig.isJiraConfigured
    @JvmStatic fun isJiraAssetsConfigured() = StockItConfig.isJiraAssetsConfigured
    @JvmStatic fun jiraAssetsSchemaId() = StockItConfig.jiraAssetsSchemaId
    @JvmStatic fun isSlackConfigured() = StockItConfig.stockitSlackWebhookUrl.isNotBlank() ||
        StockItConfig.slackWebhookUrl.isNotBlank() || StockItConfig.slackBotToken.isNotBlank()
    @JvmStatic fun slackRelayUrl() = StockItConfig.stockitSlackWebhookUrl
    @JvmStatic fun slackBotToken() = StockItConfig.slackBotToken
    @JvmStatic fun slackChannel() = StockItConfig.slackChannelId
    @JvmStatic fun sendWebhookUrl() = StockItConfig.stockitWebhookUrl
    @JvmStatic fun eventWebhookUrl() = StockItConfig.stockitEventWebhookUrl
    @JvmStatic fun assetStatusWebhookUrl() = StockItConfig.stockitAssetStatusWebhookUrl
    @JvmStatic fun webhookSecret() = StockItConfig.stockitWebhookSecret
    @JvmStatic fun apiBaseUrl() = StockItConfig.apiBaseUrl

    // ------------------------------------------------------------------ conversions

    @JvmStatic
    fun toShared(p: RoomProduct) = SharedProduct(
        id = p.id.toLong(), name = p.name ?: "", category = p.category, categoryId = p.categoryId?.toLong(),
        description = p.description, assetTag = p.assetTag, quantity = p.quantity, unitPrice = p.unitPrice,
        manufacturingDate = p.manufacturingDate, expirationDate = p.expirationDate, minThreshold = p.minThreshold,
        poNumber = p.poNumber, poDescription = p.poDescription, receivedFrom = p.receivedFrom,
        articleNumber = p.articleNumber, brand = p.brand, packagePoNumber = p.packagePoNumber,
    )

    private fun toShared(t: RoomJiraTicket) = SharedJiraTicket(
        key = t.key ?: "", summary = t.summary, status = t.status, priority = t.priority,
        dueDate = t.dueDate, description = t.description, assignee = t.assignee, reporter = t.reporter,
    )

    // ------------------------------------------------------------------ product matcher (synchrone)

    /** Produits candidats (Room) triés par pertinence, insensible aux accents + synonymes FR/EN. */
    @JvmStatic
    fun candidates(detected: String, stock: List<RoomProduct>, onlyInStock: Boolean): List<RoomProduct> {
        val byId = stock.associateBy { it.id.toLong() }
        return ProductMatcher.candidates(detected, stock.map { toShared(it) }, onlyInStock).mapNotNull { byId[it.id] }
    }

    @JvmStatic
    fun bestMatch(detected: String, stock: List<RoomProduct>, onlyInStock: Boolean): RoomProduct? {
        val m = ProductMatcher.bestMatch(detected, stock.map { toShared(it) }, onlyInStock) ?: return null
        return stock.firstOrNull { it.id.toLong() == m.id }
    }

    @JvmStatic fun categoryFor(detected: String): String = ProductMatcher.categoryFor(detected)

    @JvmStatic
    fun visionAuditDetail(detected: String?, finalName: String, confidence: Int, serialRead: Boolean): String =
        AIQualityCalculator.visionAuditDetail(detected, finalName, confidence, serialRead)

    @JvmStatic fun visionAuditAction(): String = AIQualityCalculator.AUDIT_VISION

    @JvmStatic fun normalizeSerial(raw: String?): String? = AIGateway.normalizeSerial(raw)

    // ------------------------------------------------------------------ vision Claude (comme iOS)

    @JvmStatic
    fun identify(jpeg: ByteArray, cb: VisionCallback) {
        scope.launch {
            val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
            val r = runCatching { AIGateway.identify(b64) }.getOrNull()
            main.post {
                if (r == null) cb.onResult(null, -1, null, "vision_exception")
                else cb.onResult(r.name, r.confidence, r.serial, r.error)
            }
        }
    }

    // ------------------------------------------------------------------ tickets (Claude + confiance)

    @JvmStatic
    fun assignTickets(equipment: String, qty: Int, tickets: List<RoomJiraTicket>, cb: AssignCallback) {
        scope.launch {
            val r = runCatching { TicketMatcher.assign(equipment, qty, tickets.filter { it.key != null }.map { toShared(it) }) }
            main.post {
                r.fold({ cb.onResult(it.assignments, it.error) }, { cb.onResult(null, it.message) })
            }
        }
    }

    // ------------------------------------------------------------------ Jira Assets (CMDB)

    @JvmStatic
    fun assetsCheckIn(name: String, qty: Int, brand: String?, model: String?, poNumber: String?, vendor: String?,
                      serials: List<String>, cb: OpCallback) {
        scope.launch {
            val r = if (!JiraAssetsClient.isConfigured) OpResult(false, "assets_not_configured")
            else runCatching { JiraAssetsClient.checkIn(name, qty, brand, model, poNumber, vendor, serials) }
                .getOrElse { OpResult(false, it.message) }
            main.post { cb.onResult(r.success, r.detail) }
        }
    }

    /** Sortie : objets Assets → OUT + commentaire de livraison sur le ticket Jira (identique iOS). */
    @JvmStatic
    fun assetsCheckOut(name: String, qty: Int, ticketKey: String, assignedTo: String?, aiReason: String?,
                       actor: String, cb: OpCallback) {
        scope.launch {
            val hasTicket = ticketKey.isNotBlank() && !ticketKey.equals("NO-TICKET", true)
            // Unités connues grâce à la synchronisation : on cible leurs clés exactes (identique iOS).
            val (tag, keys) = runCatching { AssetsSync.preferredKeysFor(name) }.getOrElse { null to emptyList() }
            val assets = if (JiraAssetsClient.isConfigured)
                runCatching { JiraAssetsClient.checkOut(name, qty, assignedTo ?: actor, ticketKey, keys, AssetsSync.typeIdForTag(tag)) }
                    .getOrElse { OpResult(false, it.message) }
            else OpResult(false, "assets_not_configured")
            if (assets.success && tag != null) AssetsSync.removeKeys(tag, assets.detail)
            if (hasTicket) {
                val lines = buildList {
                    add("📦 StockIT — livraison enregistrée")
                    add("Équipement : $name × $qty")
                    add("Remis par : $actor")
                    if (!assignedTo.isNullOrBlank()) add("Pour : $assignedTo")
                    if (!aiReason.isNullOrBlank()) add("Justification : $aiReason")
                    if (assets.success) add("Jira Assets : ${assets.detail} → OUT / Checked Out")
                }
                runCatching { JiraClient.addComment(ticketKey, lines.joinToString("\n")) }
            }
            main.post { cb.onResult(assets.success, assets.detail) }
        }
    }

    // ------------------------------------------------------------------ Slack test

    @JvmStatic
    fun slackTest(user: String?, cb: OpCallback) {
        scope.launch {
            val r = runCatching { SharedSlack.sendTest(user) }.getOrElse { OpResult(false, it.message) }
            main.post { cb.onResult(r.success, r.detail) }
        }
    }

    // ------------------------------------------------------------------ prévisions + brouillons

    private val DATE_FMT = "dd/MM/yyyy HH:mm"

    private fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        for (p in listOf(DATE_FMT, "dd/MM/yyyy")) {
            runCatching { SimpleDateFormat(p, Locale.FRANCE).apply { isLenient = false }.parse(s)?.time }
                .getOrNull()?.let { return it }
        }
        return 0
    }

    private fun loadSuppliersAndOrders(db: AppDatabase): Pair<List<SharedSupplier>, List<SharedPO>> {
        val suppliers = db.supplierDao().all.map {
            SharedSupplier(it.id.toLong(), it.name ?: "", it.email, it.phone, it.address, it.leadTime, it.discount)
        }
        val orders = db.purchaseOrderDao().all.map {
            SharedPO(it.id.toLong(), it.productName ?: "", it.quantity, it.supplier, it.date, it.status ?: "")
        }
        return suppliers to orders
    }

    /** Prévision des besoins (sorties réelles + demande Jira en attente, ajustée par Claude). */
    @JvmStatic
    fun forecast(context: Context, useAI: Boolean, cb: ForecastCallback) {
        val db = AppDatabase.getInstance(context)
        scope.launch {
            val result = runCatching {
                val products = db.productDao().all.map { toShared(it) }
                val outs = db.stockMovementDao().all
                    .filter { it.type == "OUT" }
                    .mapNotNull { m -> parseDate(m.date).takeIf { it > 0 }?.let { UsagePoint(m.productId.toLong(), m.quantity, it) } }
                val (suppliers, orders) = loadSuppliersAndOrders(db)
                val leadTimes = products.associate { p ->
                    p.id to (OrderDrafter.usualSupplier(p, orders, suppliers)?.leadTime ?: DemandForecaster.DEFAULT_LEAD_TIME)
                }
                val tickets = if (StockItConfig.isJiraConfigured)
                    runCatching { JiraClient.loadOpenTickets(50).tickets }.getOrNull().orEmpty() else emptyList()
                val pending = mutableMapOf<Long, Int>()
                if (tickets.isNotEmpty()) {
                    val served = db.analyzedTicketDao().all.filter { it.deliveredQty > 0 }
                    for (p in products) {
                        val key = p.name.trim().lowercase()
                        val n = tickets.count { t ->
                            ProductMatcher.mentionsSameType((t.summary ?: "") + " " + (t.description ?: "").take(300), p.name) &&
                                served.none { it.ticketId == t.key && it.equipmentKey == key }
                        }
                        if (n > 0) pending[p.id] = n
                    }
                }
                val base = DemandForecaster.forecast(products, outs, pending, leadTimes, System.currentTimeMillis())
                if (!useAI || !StockItConfig.isAIConfigured || base.isEmpty()) base
                else {
                    val focus = base.filter { it.risk != "OK" || it.dailyUsage > 0 || it.pendingDemand > 0 }.ifEmpty { base.take(10) }
                    val r = AIGateway.cimpressChat(DemandForecaster.buildPrompt(focus, tickets.mapNotNull { it.summary }), maxTokens = 1200)
                    DemandForecaster.applyClaude(base, r.text)
                }
            }.getOrElse { emptyList() }
            main.post { cb.onResult(result) }
        }
    }

    /** Brouillon de commande (fournisseur habituel + quantité + justification Claude). */
    @JvmStatic
    fun draftOrder(context: Context, f: Forecast, cb: DraftCallback) {
        val db = AppDatabase.getInstance(context)
        scope.launch {
            val room = db.productDao().getById(f.productId.toInt())
            val product = room?.let { toShared(it) }
                ?: SharedProduct(id = f.productId, name = f.productName, quantity = f.stock, minThreshold = f.minThreshold)
            val (suppliers, orders) = loadSuppliersAndOrders(db)
            val local = OrderDrafter.localDraft(f, product, OrderDrafter.usualSupplier(product, orders, suppliers))
            val draft = if (!StockItConfig.isAIConfigured) local else runCatching {
                val r = AIGateway.cimpressChat(OrderDrafter.buildPrompt(local, f), maxTokens = 250)
                val txt = r.text?.replace(Regex("[*#`]"), "")?.trim()?.takeIf { it.isNotBlank() }
                if (txt == null) local else local.copy(justification = txt.take(500), byAI = true)
            }.getOrElse { local }
            main.post { cb.onResult(draft) }
        }
    }

    /** Prévision d'un seul produit (bouton « Brouillon de commande IA » du détail produit). */
    @JvmStatic
    fun forecastFor(context: Context, productId: Int, cb: ForecastCallback) {
        forecast(context, false) { list -> cb.onResult(list.filter { it.productId == productId.toLong() }) }
    }

    // ------------------------------------------------------------------ qualité IA

    @JvmStatic
    fun aiQuality(context: Context, cb: QualityCallback) {
        val db = AppDatabase.getInstance(context)
        scope.launch {
            val tickets = db.analyzedTicketDao().all.map { it.toShared() }
            val audit = db.auditLogDao().all.filter { it.action == AIQualityCalculator.AUDIT_VISION }.map { it.toShared() }
            val q = AIQualityCalculator.compute(tickets, audit)
            main.post { cb.onResult(q) }
        }
    }

    private fun RoomTicket.toShared() = SharedTicket(
        id.toLong(), ticketId ?: "", equipmentKey ?: "", equipmentName, suggestedQty, deliveredQty, reason,
        isFulfilled, analyzedAt, fulfilledAt, jiraUpdated, ticketSummary,
    )

    private fun RoomAudit.toShared() = SharedAudit(id.toLong(), userId, action ?: "", details, timestamp, ipAddress, device)
}
