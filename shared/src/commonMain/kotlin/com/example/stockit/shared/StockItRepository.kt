package com.example.stockit.shared

import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.model.AIInsight
import com.example.stockit.shared.model.AnalyzedTicket
import com.example.stockit.shared.model.AuditLog
import com.example.stockit.shared.model.ProductCategory
import com.example.stockit.shared.model.CategoryCount
import com.example.stockit.shared.model.ChatMessage
import com.example.stockit.shared.model.Claim
import com.example.stockit.shared.model.InsightType
import com.example.stockit.shared.model.OpResult
import com.example.stockit.shared.model.Product
import com.example.stockit.shared.model.PurchaseOrder
import com.example.stockit.shared.model.Quest
import com.example.stockit.shared.model.ReportData
import com.example.stockit.shared.model.ShippingOrder
import com.example.stockit.shared.model.StockMovement
import com.example.stockit.shared.model.Supplier
import com.example.stockit.shared.model.User
import com.example.stockit.shared.ai.AIQuality
import com.example.stockit.shared.ai.AIQualityCalculator
import com.example.stockit.shared.ai.DemandForecaster
import com.example.stockit.shared.ai.Forecast
import com.example.stockit.shared.ai.OfflineAI
import com.example.stockit.shared.ai.OrderDraft
import com.example.stockit.shared.ai.OrderDrafter
import com.example.stockit.shared.ai.ProductMatcher
import com.example.stockit.shared.ai.UsagePoint
import com.example.stockit.shared.network.AIGateway
import com.example.stockit.shared.network.JiraAssetsClient
import com.example.stockit.shared.network.JiraClient
import com.example.stockit.shared.network.SlackNotifier
import com.example.stockit.shared.network.StockItApi
import com.example.stockit.shared.network.StockItReporter
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.Roles
import com.example.stockit.shared.network.Http
import com.example.stockit.shared.security.InputRules
import com.example.stockit.shared.security.JiraKeys
import com.example.stockit.shared.security.PasswordHasher
import com.example.stockit.shared.security.Sha256
import com.example.stockit.shared.security.randomId
import com.example.stockit.shared.security.toHex
import com.example.stockit.shared.platform.formatEpoch
import com.example.stockit.shared.platform.nowMs
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * StockIT — Dépôt métier partagé. Portage fidèle de controller/MainController.java :
 * mêmes règles de rôles, mêmes mouvements de stock, mêmes entrées d'audit,
 * même gamification, mêmes seeds de démo.
 *
 * Toutes les opérations sont `suspend` (Swift : `try await repo.getStock()`),
 * exécutées sur un dispatcher séquentiel (équivalent du SingleThreadExecutor Android).
 */
class StockItRepository internal constructor(
    private val db: StockItDatabase,
    private val deviceName: String,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Utilisateur connecté (MainController.currentUser). */
    var currentUser: User? = null
        private set

    val isAdmin: Boolean get() = currentUser?.isAdmin == true
    val canEditStock: Boolean get() = currentUser?.canEditStock == true

    private fun today(pattern: String) = formatEpoch(nowMs(), pattern)
    private val actorName: String get() = currentUser?.username ?: Strings.USER_SYSTEM

    // =========================================================== AUTH

    /** Échecs de connexion locale récents, par nom d'utilisateur (anti force brute). */
    private val failedLogins = mutableMapOf<String, MutableList<Long>>()

    /**
     * Accès de secours hors ligne (comptes locaux). Désactivé sauf si l'app l'autorise
     * explicitement ([StockItConfig.localLoginEnabled], builds Debug uniquement).
     * Aucun compte par défaut, aucun mot de passe en clair : vérification PBKDF2.
     */
    @Throws(Throwable::class)
    suspend fun login(username: String, password: String): User? {
        if (!StockItConfig.localLoginEnabled) return null
        val name = username.trim()
        val row = withContext(io) {
            if (isLockedOut(name)) {
                audit("LOGIN_LOCKED", "Connexion refusée (trop d'échecs) : $name", actor = name)
                return@withContext null
            }
            db.userQueries.findLocalByUsername(name).executeAsOneOrNull()
        } ?: return null
        // PBKDF2 est volontairement coûteux : hors du dispatcher base de données.
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, row.password) }
        return withContext(io) {
            if (!ok) {
                failedLogins.getOrPut(name.lowercase()) { mutableListOf() } += nowMs()
                audit("LOGIN_FAILED", "Échec de connexion locale : $name", actor = name)
                return@withContext null
            }
            failedLogins.remove(name.lowercase())
            val user = row.toModel()
            currentUser = user
            audit("LOGIN", "Utilisateur connecté: ${user.username}")
            user
        }
    }

    private fun isLockedOut(name: String): Boolean {
        val now = nowMs()
        val recent = failedLogins[name.lowercase()]?.apply { removeAll { now - it > LOCKOUT_WINDOW_MS } } ?: return false
        return recent.size >= MAX_FAILED_LOGINS
    }

    /**
     * Crée (ou réinitialise) un compte local — environnement de développement / tests UI uniquement.
     * Refusé si l'accès local est désactivé, si le nom appartient à un compte SSO, ou si le mot de passe est faible.
     */
    @Throws(Throwable::class)
    suspend fun provisionLocalUser(username: String, password: String, role: String): Boolean {
        if (!StockItConfig.localLoginEnabled) return false
        val name = username.trim()
        if (name.isEmpty() || password.length < 10) return false
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
        val r = Roles.normalize(role)
        return withContext(io) {
            val existing = db.userQueries.findByUsername(name).executeAsOneOrNull()
            when {
                existing == null -> db.userQueries.insertLocal(name, hash, r)
                existing.provider == "local" -> db.userQueries.setLocalCredentials(hash, r, existing.id)
                else -> return@withContext false
            }
            audit("PROVISION_LOCAL_USER", "Compte local $name ($r)", actor = "system")
            true
        }
    }

    /**
     * Utilisateur authentifié par Vista SSO. L'identité est le `sub` Auth0 (immuable),
     * jamais le nom affiché : un employé nommé « admin » ne récupère pas un compte existant.
     * Seuls les rôles connus sont acceptés (sinon USER). Les comptes locaux ne sont jamais modifiés.
     */
    @Throws(Throwable::class)
    suspend fun loginSso(subject: String, username: String, role: String?): User = withContext(io) {
        require(subject.isNotBlank()) { "missing Auth0 subject" }
        val r = Roles.normalize(role)
        val name = username.trim().ifEmpty { "vista.user" }
        val row = db.transactionWithResult {
            val bound = db.userQueries.findBySubject(subject).executeAsOneOrNull()
                ?: db.userQueries.findLegacySso(name).executeAsOneOrNull()?.also { db.userQueries.bindSubject(subject, it.id) }
            if (bound != null) {
                if (bound.role != r) db.userQueries.updateProfile(r, bound.points, bound.level, bound.badges, bound.id)
                db.userQueries.findById(bound.id).executeAsOne()
            } else {
                db.userQueries.insertSso(uniqueUsername(name, subject), r, subject)
                db.userQueries.findBySubject(subject).executeAsOne()
            }
        }
        val user = row.toModel()
        currentUser = user
        audit("LOGIN_SSO", "Connexion SSO Auth0 : ${user.username} (${user.role})")
        user
    }

    /** Nom affiché unique : « Sam », sinon « Sam (a1b2c3) » d'après le sub. */
    private fun uniqueUsername(name: String, subject: String): String {
        if (db.userQueries.findByUsername(name).executeAsOneOrNull() == null) return name
        val suffix = subject.filter { it.isLetterOrDigit() }.takeLast(6)
        var candidate = "$name ($suffix)"
        var i = 2
        while (db.userQueries.findByUsername(candidate).executeAsOneOrNull() != null) candidate = "$name ($suffix-${i++})"
        return candidate
    }

    fun logout() {
        currentUser = null
        // Le mode test ne survit jamais à la session de l'administrateur qui l'a activé.
        StockItConfig.testMode = false
    }

    // =========================================================== USERS

    /** Administration des comptes : ADMIN uniquement. */
    @Throws(Throwable::class)
    suspend fun getUsers(): List<User> = withContext(io) {
        if (!isAdmin) return@withContext emptyList()
        db.userQueries.getAll().executeAsList().map { it.toModel() }
    }

    @Throws(Throwable::class)
    suspend fun deleteUser(user: User): Boolean = withContext(io) {
        val me = currentUser ?: return@withContext false
        if (!me.isAdmin || user.id == me.id) return@withContext false
        db.userQueries.delete(user.id)
        audit("DELETE_USER", "Compte supprimé : ${user.username}")
        true
    }

    @Throws(Throwable::class)
    suspend fun getLeaderboard(): List<User> = withContext(io) {
        if (currentUser == null) return@withContext emptyList()
        db.userQueries.getAll().executeAsList().map { it.toModel() }.sortedByDescending { it.points }
    }

    // =========================================================== MODE TEST

    /**
     * Mode test (aucune écriture externe) : ADMIN uniquement, tracé dans l'audit,
     * désactivé automatiquement à la déconnexion.
     */
    @Throws(Throwable::class)
    suspend fun setTestMode(enabled: Boolean): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        StockItConfig.testMode = enabled
        audit(if (enabled) "TEST_MODE_ON" else "TEST_MODE_OFF", "Mode test ${if (enabled) "activé" else "désactivé"}")
        true
    }

    // =========================================================== CATEGORIES

    @Throws(Throwable::class)
    suspend fun getCategories(): List<ProductCategory> = withContext(io) {
        var list = db.categoryQueries.getAll().executeAsList()
        if (list.isEmpty()) {
            db.categoryQueries.insert(Strings.CAT_ACCESSORIES, null, "#00B0FF")
            db.categoryQueries.insert(Strings.CAT_COMPUTER, null, "#01579B")
            db.categoryQueries.insert(Strings.CAT_DESKTOP, null, "#0091EA")
            db.categoryQueries.insert(Strings.CAT_LAPTOP, null, "#80D8FF")
            db.categoryQueries.insert(Strings.CAT_MONITOR, null, "#006196")
            list = db.categoryQueries.getAll().executeAsList()
        }
        list.map { ProductCategory(it.id, it.name, it.parentId, it.labelColor) }
    }

    @Throws(Throwable::class)
    suspend fun addCategory(name: String, parentId: Long?, color: String?): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.categoryQueries.insert(name, parentId, color)
        audit("ADD_CATEGORY", Strings.auditCatAdded(name))
        true
    }

    @Throws(Throwable::class)
    suspend fun updateCategory(c: ProductCategory): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.categoryQueries.update(c.name, c.parentId, c.labelColor, c.id)
        audit("UPDATE_CATEGORY", Strings.auditCatUpdated(c.name))
        true
    }

    @Throws(Throwable::class)
    suspend fun deleteCategory(c: ProductCategory): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.categoryQueries.delete(c.id)
        audit("DELETE_CATEGORY", Strings.auditCatDeleted(c.name))
        true
    }

    // =========================================================== PRODUCTS

    @Throws(Throwable::class)
    suspend fun getStock(): List<Product> = withContext(io) {
        db.productQueries.getAll().executeAsList().map { it.toModel() }
    }

    @Throws(Throwable::class)
    suspend fun searchProducts(query: String): List<Product> = withContext(io) {
        db.productQueries.search("%${escapeLike(query)}%").executeAsList().map { it.toModel() }
    }

    /** `%` et `_` saisis par l'utilisateur sont des caractères, pas des jokers (ESCAPE '\'). */
    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Scan en rafale : si un produit porte déjà ce code (asset tag / référence), +1 sur sa quantité,
     * sinon création d'un article. Retourne "updated" / "created" / null (refusé).
     */
    @Throws(Throwable::class)
    suspend fun addOrIncrementByCode(code: String): String? {
        val trimmed = code.trim()
        if (!InputRules.isPlausibleBarcode(trimmed)) return null
        return addOrIncrementValidCode(trimmed)
    }

    private suspend fun addOrIncrementValidCode(code: String): String? {
        val existing = withContext(io) {
            db.productQueries.getAll().executeAsList().map { it.toModel() }
                .firstOrNull { it.assetTag.equals(code, true) || it.articleNumber.equals(code, true) }
        }
        if (existing != null) {
            if (!canEditStock) return null
            updateQuantity(existing, 1) ?: return null
            return "updated"
        }
        return addProduct("Produit $code", "Batch", null, "Batch Scan", code, 1, 0.0, "", "", "Batch")?.let { "created" }
    }

    @Throws(Throwable::class)
    suspend fun getProduct(id: Long): Product? = withContext(io) {
        db.productQueries.getById(id).executeAsOneOrNull()?.toModel()
    }

    /**
     * Ajout produit (ADMIN/MANAGER). Enregistre aussi le mouvement IN, l'audit
     * et +10 points. Retourne l'id créé, ou null si refusé.
     */
    @Throws(Throwable::class)
    suspend fun addProduct(
        name: String,
        category: String?,
        categoryId: Long?,
        desc: String?,
        assetTag: String?,
        quantity: Int,
        unitPrice: Double,
        mfgDate: String?,
        expDate: String?,
        reason: String?,
        poNumber: String? = null,
        poDescription: String? = null,
        receivedFrom: String? = null,
        articleNumber: String? = null,
        brand: String? = null,
        packagePoNumber: String? = null,
        /** true : les unités sont créées dans Jira Assets ici (saisie manuelle, rafale).
         *  false : l'appelant s'en charge (scan d'équipement → syncAssetsCheckIn avec n° de série). */
        syncAssets: Boolean = true,
    ): Long? {
        val id = addProductLocal(name, category, categoryId, desc, assetTag, quantity, unitPrice, mfgDate, expDate, reason,
            poNumber, poDescription, receivedFrom, articleNumber, brand, packagePoNumber) ?: return null
        if (syncAssets && quantity > 0) syncProductToAssets(id, quantity, "CREATE")
        return id
    }

    private suspend fun addProductLocal(
        name: String, category: String?, categoryId: Long?, desc: String?, assetTag: String?, quantity: Int,
        unitPrice: Double, mfgDate: String?, expDate: String?, reason: String?, poNumber: String?,
        poDescription: String?, receivedFrom: String?, articleNumber: String?, brand: String?, packagePoNumber: String?,
    ): Long? = withContext(io) {
        if (!canEditStock) return@withContext null
        fun String?.nn() = this?.takeIf { it.isNotEmpty() }
        val newId = db.transactionWithResult {
            db.productQueries.insert(
                name, category, categoryId, desc, assetTag, quantity.toLong(), unitPrice,
                mfgDate, expDate, 5, poNumber.nn(), poDescription.nn(), receivedFrom.nn(),
                articleNumber.nn(), brand.nn(), packagePoNumber.nn(),
            )
            db.productQueries.lastInsertId().executeAsOne()
        }
        var movReason = reason.nn() ?: Strings.MOV_NEW_PRODUCT
        if (!poNumber.isNullOrEmpty()) movReason += " [PO $poNumber]"
        insertMovement(newId, name, "IN", quantity, movReason, null)
        audit("ADD_PRODUCT", Strings.auditProdAdded(name, quantity))
        addPointsInternal(10)
        newId
    }

    /**
     * Ajustement +/- (tout utilisateur connecté). Quantité plancher 0.
     * [syncAssets] : répercute l'écart réel dans Jira Assets (false si l'appelant s'en charge).
     */
    @Throws(Throwable::class)
    suspend fun updateQuantity(product: Product, delta: Int, syncAssets: Boolean = true): Product? {
        if (delta == 0 || delta !in -InputRules.MAX_STOCK_DELTA..InputRules.MAX_STOCK_DELTA) return null
        val r = withContext(io) {
            if (currentUser?.canAdjustStock != true) return@withContext null
            val fresh = db.productQueries.getById(product.id).executeAsOneOrNull()?.toModel() ?: return@withContext null
            val newQty = (fresh.quantity.toLong() + delta).coerceIn(0L, InputRules.MAX_STOCK_QUANTITY.toLong()).toInt()
            db.productQueries.updateQuantity(newQty.toLong(), fresh.id)
            insertMovement(fresh.id, fresh.name, if (delta > 0) "IN" else "OUT", kotlin.math.abs(delta), Strings.MOV_MANUAL_ADJ, null)
            audit("UPDATE_QUANTITY", Strings.auditQtyAdjusted(fresh.name, delta))
            fresh to (newQty - fresh.quantity)
        } ?: return null
        val (fresh, applied) = r
        if (syncAssets && applied != 0) syncProductToAssets(fresh.id, applied, if (applied > 0) "ADJUST_IN" else "ADJUST_OUT")
        return fresh.copy(quantity = fresh.quantity + applied)
    }

    @Throws(Throwable::class)
    suspend fun updateProduct(p: Product): Boolean {
        val old = withContext(io) { db.productQueries.getById(p.id).executeAsOneOrNull()?.toModel() }
        if (!updateProductLocal(p)) return false
        if (old != null) {
            if (old.name != p.name || old.brand != p.brand) renameInAssets(old, p.name, p.brand)
            val delta = p.quantity - old.quantity
            if (delta != 0) syncProductToAssets(p.id, delta, if (delta > 0) "ADJUST_IN" else "ADJUST_OUT")
        }
        return true
    }

    private suspend fun updateProductLocal(p: Product): Boolean = withContext(io) {
        if (!canEditStock) return@withContext false
        db.productQueries.update(
            p.name, p.category, p.categoryId, p.description, p.assetTag, p.quantity.toLong(), p.unitPrice,
            p.manufacturingDate, p.expirationDate, p.minThreshold.toLong(), p.poNumber, p.poDescription,
            p.receivedFrom, p.articleNumber, p.brand, p.packagePoNumber, p.id,
        )
        audit("UPDATE_PRODUCT", Strings.auditProdUpdated(p.name))
        true
    }

    @Throws(Throwable::class)
    suspend fun deleteProduct(p: Product): Boolean {
        if (!isAdmin) return false
        val fresh = withContext(io) { db.productQueries.getById(p.id).executeAsOneOrNull()?.toModel() } ?: p
        // Jira : les unités en stock passent à « Inactive » (aucune suppression dans la CMDB).
        if (com.example.stockit.shared.network.JiraAssetsClient.isConfigured && isSyncedWithAssets(fresh) && fresh.quantity > 0) {
            pushDelta(fresh, -fresh.quantity, "DELETE", inactive = true)
        }
        return deleteProductLocal(fresh)
    }

    private suspend fun deleteProductLocal(p: Product): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.productQueries.delete(p.id)
        insertMovement(p.id, p.name, "OUT", p.quantity, Strings.MOV_DELETED, null)
        audit("DELETE_PRODUCT", Strings.auditProdDeleted(p.name))
        true
    }

    @Throws(Throwable::class)
    suspend fun recordMovement(productId: Long, productName: String, type: String, qty: Int, reason: String?, comment: String?): Boolean =
        withContext(io) {
            if (currentUser?.canAdjustStock != true) return@withContext false
            if (type != "IN" && type != "OUT" || qty !in 1..InputRules.MAX_STOCK_DELTA) return@withContext false
            insertMovement(productId, productName, type, qty, reason, comment)
            true
        }

    /**
     * Sortie liée à un ticket Jira. Retourne false si stock insuffisant.
     * (MainController.recordExitToTicket)
     */
    @Throws(Throwable::class)
    suspend fun recordExitToTicket(product: Product, qty: Int, ticketId: String, assignmentReason: String?): Boolean =
        withContext(io) {
            if (currentUser?.canAdjustStock != true) return@withContext false
            if (qty !in 1..InputRules.MAX_STOCK_DELTA || !JiraKeys.isValidOrNoTicket(ticketId)) return@withContext false
            val fresh = db.productQueries.getById(product.id).executeAsOneOrNull()?.toModel()
                ?: return@withContext false
            if (fresh.quantity < qty) return@withContext false
            db.productQueries.updateQuantity((fresh.quantity - qty).toLong(), fresh.id)
            val reason = "Sortie sur ticket $ticketId" +
                (if (!assignmentReason.isNullOrEmpty()) " — $assignmentReason" else "")
            db.stockMovementQueries.insert(
                fresh.id, fresh.name, "OUT", qty.toLong(), today("dd/MM/yyyy HH:mm"), reason, actorName, null,
                ticketId, assignmentReason, nowMs(),
            )
            notifyStockEvent(fresh.id, fresh.name, "OUT", qty, reason, ticketId)
            audit("EXIT_TO_TICKET", "${fresh.name} x$qty → $ticketId")
            addPointsInternal(5)
            true
        }

    // =========================================================== SYNCHRO JIRA ASSETS → INVENTAIRE

    /** Préfixe du champ assetTag des produits issus de Jira Assets (clé de regroupement). */
    private val ASSETS_TAG = "JA:"

    /** Noms des produits de démonstration créés quand le stock était vide (supprimés à la 1re synchro). */
    private val DEMO_TAGS = setOf("DELL-LAT-01", "LOGI-MX-02", "HP-SCR-24")

    /** Date (ms) de la dernière synchronisation réussie, 0 si jamais. */
    var lastAssetsSyncMs: Long = 0
        private set

    data class SyncReport(
        val success: Boolean,
        val error: String?,
        val products: Int,
        val units: Int,
        val created: Int,
        val updated: Int,
        val removed: Int,
        val demoRemoved: Int,
    )

    /**
     * Jira Assets = source de vérité. Lit tous les objets, regroupe les unités « In stock » par modèle,
     * puis aligne l'inventaire local :
     *  - produit existant (même modèle) → quantité / marque / séries mises à jour ;
     *  - nouveau modèle → produit créé ;
     *  - produit synchronisé qui n'a plus d'unité en stock dans Jira → quantité 0 (conservé pour l'historique) ;
     *  - produits de démonstration → supprimés.
     * Les produits saisis à la main (non liés à Jira) ne sont pas touchés.
     * Lecture seule côté Jira : aucune écriture.
     */
    /** Lecture Jira Assets (remplaçable dans les tests). */
    internal var assetsFetcher: suspend () -> JiraAssetsClient.FetchResult = { JiraAssetsClient.fetchAll() }

    /**
     * Garde-fous : il faut être connecté ; un résultat tronqué ou vide (panne, droits) ne remet
     * jamais de stock à 0 ; l'audit garde l'avant/après ; seuls ADMIN / MANAGER renvoient des produits vers Jira.
     */
    @Throws(Throwable::class)
    suspend fun syncFromJiraAssets(): SyncReport {
        if (currentUser == null) return SyncReport(false, "not_signed_in", 0, 0, 0, 0, 0, 0)
        val fetched = assetsFetcher()
        val units = fetched.units ?: return SyncReport(false, fetched.error, 0, 0, 0, 0, 0, 0)
        val groups = com.example.stockit.shared.ai.AssetGrouping.group(units)
        var created = 0; var updated = 0; var removed = 0; var demo = 0
        val changes = mutableListOf<String>()
        val canZero = fetched.complete && units.isNotEmpty()
        withContext(io) {
            db.transaction {
                val all = db.productQueries.getAll().executeAsList()
                // Démo
                all.filter { it.assetTag in DEMO_TAGS }.forEach { db.productQueries.delete(it.id); demo++ }
                val synced = all.filter { it.assetTag?.startsWith(ASSETS_TAG) == true }.associateBy { it.assetTag!! }
                // Produits locaux non liés (ex. créés par un scan) : rattachés à leur modèle Jira s'ils correspondent.
                val unlinked = all.filter { p ->
                    p.assetTag !in DEMO_TAGS && p.assetTag?.startsWith(ASSETS_TAG) != true && p.category != "Batch"
                }.toMutableList()
                val seen = mutableSetOf<String>()
                assetKeysByTag.clear(); assetTypeByTag.clear(); assetSerialsByTag.clear()
                for (g in groups) {
                    val tag = ASSETS_TAG + g.key
                    seen += tag
                    assetKeysByTag[tag] = g.objectKeys
                    assetSerialsByTag[tag] = g.unitSerials
                    assetTypeByTag[tag] = g.typeId
                    val category = categoryForType(g.typeId)
                    val serialNote = if (g.serials.isEmpty()) null else "S/N : " + g.serials.take(20).joinToString(", ") +
                        (if (g.serials.size > 20) " (+${g.serials.size - 20})" else "")
                    val existing = synced[tag] ?: unlinked.firstOrNull {
                        com.example.stockit.shared.ai.AssetGrouping.canonical(it.name ?: "") ==
                            com.example.stockit.shared.ai.AssetGrouping.canonical(g.name)
                    }?.also { unlinked.remove(it) }
                    if (existing != null) {
                        db.productQueries.update(
                            g.name, category, existing.categoryId, serialNote ?: existing.description, tag,
                            g.quantity.toLong(), existing.unitPrice, existing.manufacturingDate, existing.expirationDate,
                            existing.minThreshold, g.poNumber ?: existing.poNumber, existing.poDescription,
                            g.vendor ?: existing.receivedFrom, existing.articleNumber, g.brand ?: existing.brand,
                            existing.packagePoNumber, existing.id,
                        )
                        if (existing.quantity != g.quantity.toLong() || existing.name != g.name) {
                            updated++
                            changes += "${existing.name} ${existing.quantity}→${g.quantity}"
                        }
                    } else {
                        db.productQueries.insert(
                            g.name, category, null, serialNote, tag, g.quantity.toLong(), 0.0, null, null, 5,
                            g.poNumber, null, g.vendor, null, g.brand, null,
                        )
                        created++
                    }
                }
                // Modèles synchronisés qui n'ont plus d'unité « In stock » dans Jira → 0.
                val missing = synced.values.filter { it.assetTag !in seen && it.quantity != 0L }
                if (canZero) {
                    missing.forEach {
                        db.productQueries.updateQuantity(0, it.id); removed++
                        changes += "${it.name} ${it.quantity}→0"
                    }
                } else if (missing.isNotEmpty()) {
                    audit("ASSETS_SYNC_PARTIAL", "Résultat Jira incomplet ou vide : ${missing.size} produit(s) conservé(s) sans mise à 0")
                }
                audit("ASSETS_SYNC", ("Jira Assets → inventaire : ${groups.size} modèles, ${groups.sumOf { it.quantity }} unités " +
                    "(+$created, ~$updated, 0:$removed, démo:$demo)" +
                    (if (changes.isEmpty()) "" else " — " + changes.joinToString("; "))).take(2000))
            }
        }
        // Produits créés à la main (ou hors ligne) qui n'existent pas encore dans Jira → envoyés (ADMIN / MANAGER).
        val pushed = if (canEditStock) pushLocalOnlyProducts() else 0
        lastAssetsSyncMs = nowMs()
        return SyncReport(true, null, groups.size + pushed, groups.sumOf { it.quantity }, created + pushed, updated, removed, demo)
    }

    /**
     * Envoie à Jira Assets les produits locaux non liés (quantité > 0) : création des unités « In stock »,
     * puis liaison du produit (assetTag JA:…) pour qu'il ne soit plus jamais envoyé deux fois.
     * Les doublons locaux (même modèle) sont fusionnés avant l'envoi.
     */
    private suspend fun pushLocalOnlyProducts(): Int {
        if (!com.example.stockit.shared.network.JiraAssetsClient.isConfigured || StockItConfig.testMode) return 0
        val local = withContext(io) {
            db.productQueries.getAll().executeAsList().map { it.toModel() }
                .filter { !isSyncedWithAssets(it) && it.assetTag !in DEMO_TAGS && it.quantity > 0 && it.name.isNotBlank() }
        }
        if (local.isEmpty()) return 0
        // Fusion des doublons locaux (« Clavier Test iOS » ×3 → 1 produit)
        val byModel = local.groupBy { com.example.stockit.shared.ai.AssetGrouping.canonical(it.name) }
        var pushed = 0
        for ((_, dups) in byModel) {
            val keep = dups.first()
            val qty = dups.sumOf { it.quantity }
            if (dups.size > 1) withContext(io) {
                db.productQueries.updateQuantity(qty.toLong(), keep.id)
                dups.drop(1).forEach { db.productQueries.delete(it.id) }
                audit("MERGE_DUPLICATES", "${keep.name} : ${dups.size} fiches fusionnées (qté $qty)")
            }
            val r = com.example.stockit.shared.network.JiraAssetsClient.checkIn(
                keep.name, qty, keep.brand, keep.articleNumber, keep.poNumber, keep.receivedFrom, emptyList(),
            )
            withContext(io) {
                audit(if (r.success) "ASSETS_PUSH" else "ASSETS_PUSH_FAILED", "${keep.name} x$qty → ${r.detail ?: ""}".take(400))
                if (r.success) {
                    db.productQueries.setAssetTag(localAssetsTag(keep.name, keep.brand), keep.id)
                    pushed++
                }
            }
        }
        return pushed
    }

    private fun categoryForType(typeId: String): String = when (typeId) {
        StockItConfig.jiraAssetsTypeComputers -> "Informatique"
        StockItConfig.jiraAssetsTypeMonitors -> "Écran"
        else -> "Périphérique"
    }

    /** Clés Jira (SIT-…) des unités « In stock » de chaque produit synchronisé (dernière synchro). */
    private val assetKeysByTag = mutableMapOf<String, List<String>>()
    private val assetSerialsByTag = mutableMapOf<String, List<String?>>()
    private val assetTypeByTag = mutableMapOf<String, String>()

    private fun localAssetsTag(name: String, brand: String?): String {
        val typeId = com.example.stockit.shared.network.JiraAssetsClient.typeIdFor(
            com.example.stockit.shared.network.JiraAssetsClient.kindFor(name))
        return ASSETS_TAG + com.example.stockit.shared.ai.AssetGrouping.keyForLocal(typeId, name, brand)
    }

    /**
     * Répercute dans Jira Assets une modification faite dans l'app. Ne bloque jamais l'action locale ;
     * le résultat est tracé dans l'audit. Retourne null si Jira Assets n'est pas configuré.
     *
     *  - delta > 0 : création de [delta] unités « In stock » (et liaison du produit s'il ne l'était pas) ;
     *  - delta < 0 : |delta| unités passent à OUT (ajustement / sortie) ;
     *  - remove    : toutes les unités en stock passent à « Inactive » (suppression du produit).
     */
    @Throws(Throwable::class)
    suspend fun syncProductToAssets(productId: Long, delta: Int, reason: String): OpResult? {
        if (!com.example.stockit.shared.network.JiraAssetsClient.isConfigured || delta == 0) return null
        val p = withContext(io) { db.productQueries.getById(productId).executeAsOneOrNull()?.toModel() } ?: return null
        return pushDelta(p, delta, reason)
    }

    private suspend fun pushDelta(p: Product, delta: Int, reason: String, inactive: Boolean = false): OpResult {
        val client = com.example.stockit.shared.network.JiraAssetsClient
        val r = if (delta > 0) {
            client.checkIn(p.name, delta, p.brand, p.articleNumber, p.poNumber, p.receivedFrom, emptyList())
        } else {
            val tag = p.assetTag?.takeIf { it.startsWith(ASSETS_TAG) }
            val keys = tag?.let { assetKeysByTag[it] }.orEmpty()
            if (inactive) client.checkOut(p.name, -delta, actorName, null, keys, tag?.let { assetTypeByTag[it] },
                client.STATUS_INACTIVE, client.SUB_STATUS_DELETED)
            else client.checkOut(p.name, -delta, actorName, null, keys, tag?.let { assetTypeByTag[it] })
        }
        withContext(io) {
            audit(if (r.success) "ASSETS_$reason" else "ASSETS_${reason}_FAILED", "${p.name} ${if (delta > 0) "+" else ""}$delta → ${r.detail ?: ""}".take(400))
            if (r.success && delta > 0 && !isSyncedWithAssets(p)) db.productQueries.setAssetTag(localAssetsTag(p.name, p.brand), p.id)
        }
        if (r.success && delta < 0) {
            val tag = p.assetTag ?: ""
            val out = r.detail.orEmpty().split(',', ' ').map { it.trim() }.toSet()
            assetKeysByTag[tag] = assetKeysByTag[tag].orEmpty().filterNot { it in out }
        }
        return r
    }

    /** Nom modifié dans l'app → objets Jira renommés (et clé de liaison mise à jour). */
    @Throws(Throwable::class)
    suspend fun renameInAssets(old: Product, newName: String, newBrand: String?): OpResult? {
        val client = com.example.stockit.shared.network.JiraAssetsClient
        if (!client.isConfigured || (old.name == newName && old.brand == newBrand)) return null
        val tag = old.assetTag?.takeIf { it.startsWith(ASSETS_TAG) } ?: return null
        val keys = assetKeysByTag[tag].orEmpty()
        val serials = assetSerialsByTag[tag].orEmpty()
        val typeId = assetTypeByTag[tag] ?: client.typeIdFor(client.kindFor(newName))
        val r = if (keys.isEmpty()) OpResult(false, "no_asset_found")
        else client.rename(keys.mapIndexed { i, k -> k to serials.getOrNull(i) }, typeId, newName, newBrand)
        withContext(io) {
            audit(if (r.success) "ASSETS_RENAME" else "ASSETS_RENAME_FAILED", "${old.name} → $newName : ${r.detail ?: ""}".take(400))
            if (r.success) {
                val newTag = ASSETS_TAG + com.example.stockit.shared.ai.AssetGrouping.keyForLocal(typeId, newName, newBrand)
                db.productQueries.setAssetTag(newTag, old.id)
                assetKeysByTag[newTag] = keys; assetSerialsByTag[newTag] = serials; assetTypeByTag[newTag] = typeId
            }
        }
        return r
    }

    /** true si le produit vient de Jira Assets (sa quantité est pilotée par Jira). */
    fun isSyncedWithAssets(p: Product): Boolean = p.assetTag?.startsWith(ASSETS_TAG) == true

    // =========================================================== JIRA ASSETS + TICKET

    /**
     * Entrée en stock → création des objets dans Jira Assets (CMDB).
     * Appelé après addProduct / updateQuantity. Ne bloque jamais l'enregistrement local :
     * l'échec est retourné (detail) et tracé dans l'audit.
     */
    @Throws(Throwable::class)
    suspend fun syncAssetsCheckIn(
        name: String, qty: Int, brand: String?, model: String?, poNumber: String?, vendor: String?, serials: List<String>,
    ): OpResult {
        if (currentUser?.canAdjustStock != true) return OpResult(false, "permission_denied")
        if (qty !in 1..InputRules.MAX_STOCK_DELTA) return OpResult(false, "invalid_quantity")
        if (!JiraAssetsClient.isConfigured) return OpResult(false, "assets_not_configured")
        val r = JiraAssetsClient.checkIn(name, qty, brand, model, poNumber, vendor, serials)
        withContext(io) {
            audit(if (r.success) "ASSETS_CHECK_IN" else "ASSETS_CHECK_IN_FAILED", "$name x$qty → ${r.detail ?: ""}".take(400))
        }
        return r
    }

    /**
     * Sortie de stock → objets Assets passés à « OUT / Checked Out » + commentaire sur le ticket Jira.
     * [assignedTo] : demandeur du ticket (reporter), sinon technicien connecté.
     */
    @Throws(Throwable::class)
    suspend fun syncAssetsCheckOut(
        name: String, qty: Int, ticketKey: String, assignedTo: String?, aiReason: String?,
    ): OpResult {
        if (currentUser?.canAdjustStock != true) return OpResult(false, "permission_denied")
        if (qty !in 1..InputRules.MAX_STOCK_DELTA || !JiraKeys.isValidOrNoTicket(ticketKey)) return OpResult(false, "invalid_request")
        val hasTicket = ticketKey != JiraKeys.NO_TICKET
        val tag = withContext(io) {
            db.productQueries.getAll().executeAsList().firstOrNull { it.name == name && it.assetTag?.startsWith(ASSETS_TAG) == true }?.assetTag
        }
        val keys = tag?.let { assetKeysByTag[it] }.orEmpty()
        val assets = if (JiraAssetsClient.isConfigured) {
            JiraAssetsClient.checkOut(name, qty, assignedTo ?: actorName, ticketKey, keys, tag?.let { assetTypeByTag[it] })
        } else OpResult(false, "assets_not_configured")
        // Les unités sorties ne sont plus « In stock » : on les retire de la liste locale.
        if (assets.success && tag != null) {
            val out = assets.detail.orEmpty().split(',', ' ').map { it.trim() }.filter { it.startsWith("SIT-") || it.contains('-') }.toSet()
            assetKeysByTag[tag] = keys.filterNot { it in out }
        }
        withContext(io) {
            audit(if (assets.success) "ASSETS_CHECK_OUT" else "ASSETS_CHECK_OUT_FAILED", "$name x$qty ($ticketKey) → ${assets.detail ?: ""}".take(400))
        }
        if (hasTicket) {
            val lines = buildList {
                add("📦 StockIT — livraison enregistrée")
                add("Équipement : $name × $qty")
                add("Remis par : $actorName")
                if (!assignedTo.isNullOrBlank()) add("Pour : $assignedTo")
                if (!aiReason.isNullOrBlank()) add("Justification : $aiReason")
                if (assets.success) add("Jira Assets : ${assets.detail} → OUT / Checked Out")
            }
            val c = JiraClient.addComment(ticketKey, lines.joinToString("\n"))
            withContext(io) { audit(if (c.success) "JIRA_COMMENT" else "JIRA_COMMENT_FAILED", "$ticketKey → ${c.detail ?: ""}".take(300)) }
        }
        return assets
    }

    // =========================================================== CACHE TICKETS

    private fun equipmentKeyOf(name: String?) = name?.trim()?.lowercase() ?: ""

    @Throws(Throwable::class)
    suspend fun saveLlmAnalysis(ticketId: String, equipmentName: String, ticketSummary: String?, suggestedQty: Int, reason: String?): Unit =
        withContext(io) {
            if (currentUser?.canAdjustStock != true || !JiraKeys.isValid(ticketId)) return@withContext
            val key = equipmentKeyOf(equipmentName)
            val existing = db.analyzedTicketQueries.getByTicketAndEquipment(ticketId, key).executeAsOneOrNull()
            val now = nowMs()
            if (existing == null) {
                db.analyzedTicketQueries.insert(ticketId, key, equipmentName, suggestedQty.toLong(), 0, reason, false, now, 0, null, ticketSummary)
            } else {
                db.analyzedTicketQueries.update(
                    existing.equipmentName, suggestedQty.toLong(), existing.deliveredQty, reason, existing.fulfilled,
                    now, existing.fulfilledAt, existing.jiraUpdated,
                    if (!ticketSummary.isNullOrEmpty()) ticketSummary else existing.ticketSummary, existing.id,
                )
            }
        }

    @Throws(Throwable::class)
    suspend fun recordDeliveryToTicket(ticketId: String, equipmentName: String, qty: Int, reasonIfManual: String?): Unit =
        withContext(io) {
            if (currentUser?.canAdjustStock != true || qty !in 1..InputRules.MAX_STOCK_DELTA || !JiraKeys.isValid(ticketId)) return@withContext
            val key = equipmentKeyOf(equipmentName)
            val t = db.analyzedTicketQueries.getByTicketAndEquipment(ticketId, key).executeAsOneOrNull()
            val now = nowMs()
            if (t == null) {
                db.analyzedTicketQueries.insert(
                    ticketId, key, equipmentName, qty.toLong(), qty.toLong(), reasonIfManual ?: "Choix manuel",
                    true, now, now, null, null,
                )
            } else {
                val delivered = t.deliveredQty + qty
                val nowFulfilled = !t.fulfilled && t.suggestedQty > 0 && delivered >= t.suggestedQty
                db.analyzedTicketQueries.update(
                    t.equipmentName, t.suggestedQty, delivered, t.reason, t.fulfilled || nowFulfilled,
                    t.analyzedAt, if (nowFulfilled) now else t.fulfilledAt, t.jiraUpdated, t.ticketSummary, t.id,
                )
            }
        }

    @Throws(Throwable::class)
    suspend fun getFullyFulfilledTicketIds(): List<String> = withContext(io) {
        db.analyzedTicketQueries.fullyFulfilledTicketIds().executeAsList()
    }

    @Throws(Throwable::class)
    suspend fun getAnalyzedTickets(): List<AnalyzedTicket> = withContext(io) {
        db.analyzedTicketQueries.getAll().executeAsList().map {
            AnalyzedTicket(
                it.id, it.ticketId, it.equipmentKey, it.equipmentName, it.suggestedQty.toInt(), it.deliveredQty.toInt(),
                it.reason, it.fulfilled, it.analyzedAt, it.fulfilledAt, it.jiraUpdated, it.ticketSummary,
            )
        }
    }

    // =========================================================== MOUVEMENTS / AUDIT / RAPPORTS

    /** Admin : tous les mouvements ; sinon ceux de l'utilisateur. (plus récent d'abord) */
    @Throws(Throwable::class)
    suspend fun getStockMovements(): List<StockMovement> = withContext(io) {
        val u = currentUser
        when {
            isAdmin -> db.stockMovementQueries.getAll().executeAsList()
            u != null -> db.stockMovementQueries.getByUser(u.username).executeAsList()
            else -> emptyList()
        }.map { it.toModel() }
    }

    @Throws(Throwable::class)
    suspend fun getProductMovements(productId: Long): List<StockMovement> = withContext(io) {
        db.stockMovementQueries.getByProduct(productId).executeAsList().map { it.toModel() }
    }

    @Throws(Throwable::class)
    /** Journal complet : ADMIN / MANAGER uniquement. */
    suspend fun getAuditLogs(): List<AuditLog> = withContext(io) {
        if (!canEditStock) return@withContext emptyList()
        db.auditLogQueries.getAll().executeAsList().map {
            AuditLog(it.id, it.userId, it.action, it.details, it.timestamp, it.ipAddress, it.device)
        }
    }

    @Throws(Throwable::class)
    suspend fun getReportData(): ReportData = withContext(io) {
        if (db.productQueries.totalQuantity().executeAsOne() == 0L) seedStockInternal()
        ReportData(
            totalQuantity = db.productQueries.totalQuantity().executeAsOne().toInt(),
            lowStockCount = db.productQueries.lowStockCount().executeAsOne().toInt(),
            totalValue = db.productQueries.totalValue().executeAsOne(),
            outOfStockCount = db.productQueries.outOfStockCount().executeAsOne().toInt(),
            categories = db.productQueries.countByCategory().executeAsList().map {
                CategoryCount(it.category, it.total.toInt())
            },
        )
    }

    @Throws(Throwable::class)
    suspend fun seedStock(): Unit = withContext(io) { seedStockInternal() }

    private fun seedStockInternal() {
        // Avec Jira Assets, le stock réel vient de la synchronisation : pas de données de démonstration.
        if (com.example.stockit.shared.network.JiraAssetsClient.isConfigured) return
        if (db.productQueries.totalQuantity().executeAsOne() != 0L) return
        fun p(n: String, c: String, d: String, tag: String, q: Long, price: Double, mfg: String) =
            db.productQueries.insert(n, c, null, d, tag, q, price, mfg, "", 5, null, null, null, null, null, null)
        p("Laptop Dell Latitude", "Informatique", "Ordinateur portable", "DELL-LAT-01", 15, 1200.0, "10/01/2024")
        p("Souris Logitech MX", "Périphérique", "Souris ergonomique", "LOGI-MX-02", 4, 85.0, "15/01/2024")
        p("Ecran HP 24 pouces", "Informatique", "Moniteur Full HD", "HP-SCR-24", 2, 180.0, "20/01/2024")
        val t = nowMs()
        db.stockMovementQueries.insert(1, "Laptop Dell Latitude", "IN", 15, "10/06/2024", "Réception", "admin", "Arrivée stock", null, null, t)
        db.stockMovementQueries.insert(3, "Ecran HP 24 pouces", "OUT", 1, "12/06/2024", "Prêt", "Thomas", "Sortie pour bureau 302", null, null, t)
    }

    /** CSV d'inventaire (ReportWorker Android). */
    @Throws(Throwable::class)
    suspend fun buildStockCsv(): String = withContext(io) {
        buildString {
            append("ID,Nom,Quantite,Prix\n")
            db.productQueries.getAll().executeAsList().forEach {
                append(it.id).append(',').append(csvCell(it.name ?: "")).append(',').append(it.quantity).append(',').append(it.unitPrice).append('\n')
            }
        }
    }

    /**
     * Cellule CSV sûre pour Excel / Sheets : toujours entre guillemets, guillemets doublés,
     * et préfixe « ' » devant = + - @ tabulation / retour chariot (injection de formules).
     */
    internal fun csvCell(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    /** Envoi du rapport planifié au workflow n8n (ReportWorker.doWork). */
    @Throws(Throwable::class)
    suspend fun sendScheduledReport(type: String = "DAILY"): OpResult {
        val products = getStock()
        val csv = buildStockCsv()
        val msg = "📊 Rapport de stock automatique généré par le worker planifié.\n\n" +
            "• Type              : $type\n" +
            "• Nombre d'articles : ${products.size}\n\n" +
            "Contenu CSV :\n$csv"
        return StockItReporter.sendReport("StockIT-Worker", StockItConfig.defaultRecipientEmail, "Rapport $type", msg)
    }

    // =========================================================== ACHATS

    @Throws(Throwable::class)
    suspend fun getPurchaseOrders(): List<PurchaseOrder> = withContext(io) {
        db.purchaseOrderQueries.getAll().executeAsList().map {
            PurchaseOrder(it.id, it.productName, it.quantity.toInt(), it.supplier, it.date, it.status)
        }
    }

    @Throws(Throwable::class)
    suspend fun createPurchaseOrder(product: String, qty: Int, supplier: String?): Boolean = withContext(io) {
        if (!canEditStock) return@withContext false
        db.purchaseOrderQueries.insert(product, qty.toLong(), supplier, today("dd/MM/yyyy"), Strings.PURCHASE_PENDING)
        audit("CREATE_PURCHASE", Strings.auditPurchaseCreated(product, qty))
        true
    }

    @Throws(Throwable::class)
    suspend fun updatePurchaseOrder(o: PurchaseOrder): Boolean = withContext(io) {
        if (!canEditStock) return@withContext false
        db.purchaseOrderQueries.update(o.productName, o.quantity.toLong(), o.supplier, o.date, o.status, o.id)
        audit("UPDATE_PURCHASE", "Commande mise à jour: ${o.productName}")
        true
    }

    @Throws(Throwable::class)
    suspend fun deletePurchaseOrder(o: PurchaseOrder): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.purchaseOrderQueries.delete(o.id)
        audit("DELETE_PURCHASE", "Commande supprimée: ${o.productName}")
        true
    }

    /**
     * Valide la réception d'un PO après OCR facture (ReceivePackageActivity, mode A) :
     * statut "Reçu" + Slack + e-mail n8n + ticket Jira de traçabilité.
     */
    @Throws(Throwable::class)
    suspend fun validatePurchaseReception(po: PurchaseOrder, noteSupplier: String?, detectedPoCount: Int): OpResult {
        if (!updatePurchaseOrder(po.copy(status = Strings.PURCHASE_RECEIVED))) return OpResult(false, tr("Permission refusée", "Permission denied"))
        val label = "PO#${po.id} — ${po.productName} x${po.quantity}"
        background.launch {
            SlackNotifier.send(":white_check_mark: [StockIT] Reception validee par OCR : $label (fournisseur ${po.supplier})")
            StockItReporter.sendEvent(
                "Réception PO#${po.id} validée",
                "📦 Un bon de commande vient d'être réceptionné et validé par OCR mobile.\n\n" +
                    "• PO          : #${po.id}\n" +
                    "• Produit     : ${po.productName}\n" +
                    "• Quantité    : ${po.quantity}\n" +
                    "• Fournisseur : ${po.supplier}\n\n" +
                    "Statut du PO passé à \"Reçu\".",
                StockItConfig.defaultRecipientEmail,
            )
            JiraClient.createTask(
                StockItConfig.jiraProjectKey,
                "[StockIT] Reception PO#${po.id} - ${po.productName}",
                "Colis recu et valide via OCR mobile.\nFournisseur DB: ${po.supplier}, quantite: ${po.quantity}.\n" +
                    "Supplier facture: $noteSupplier.\n$detectedPoCount PO detecte(s) au total.",
            )
        }
        return OpResult(true, label)
    }

    // =========================================================== FOURNISSEURS

    @Throws(Throwable::class)
    suspend fun getSuppliers(): List<Supplier> = withContext(io) {
        var list = db.supplierQueries.getAll().executeAsList()
        if (list.isEmpty()) {
            db.supplierQueries.insert("Dell France", "contact@dell.fr", "+33 1 00 00 00 00", "Montpellier", 5, 10.0)
            db.supplierQueries.insert("Apple Store", "business@apple.com", "+33 1 11 11 11 11", "Paris", 3, 5.0)
            db.supplierQueries.insert("Logitech Pro", "sales@logitech.com", "+41 21 000 00 00", "Lausanne", 7, 15.0)
            list = db.supplierQueries.getAll().executeAsList()
        }
        list.map { Supplier(it.id, it.name, it.email, it.phone, it.address, it.leadTime.toInt(), it.discount) }
    }

    @Throws(Throwable::class)
    suspend fun addSupplier(name: String, email: String?, phone: String?, address: String?, leadTime: Int, discount: Double): Boolean =
        withContext(io) {
            if (!isAdmin) return@withContext false
            db.supplierQueries.insert(name, email, phone, address, leadTime.toLong(), discount)
            audit("ADD_SUPPLIER", Strings.auditSupAdded(name))
            true
        }

    @Throws(Throwable::class)
    suspend fun updateSupplier(s: Supplier): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.supplierQueries.update(s.name, s.email, s.phone, s.address, s.leadTime.toLong(), s.discount, s.id)
        audit("UPDATE_SUPPLIER", Strings.auditSupUpdated(s.name))
        true
    }

    @Throws(Throwable::class)
    suspend fun deleteSupplier(s: Supplier): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.supplierQueries.delete(s.id)
        audit("DELETE_SUPPLIER", Strings.auditSupDeleted(s.name))
        true
    }

    // =========================================================== EXPÉDITIONS

    @Throws(Throwable::class)
    suspend fun getShippingOrders(): List<ShippingOrder> = withContext(io) {
        if (currentUser == null) return@withContext emptyList()
        var list = db.shippingOrderQueries.getAll().executeAsList()
        if (list.isEmpty()) {
            db.shippingOrderQueries.insert("Client Vistaprint", "Laptop Dell x2", "Paris", "TRACK123", "", "PENDING")
            list = db.shippingOrderQueries.getAll().executeAsList()
        }
        list.map { ShippingOrder(it.id, it.customerName, it.itemsSummary, it.shippingAddress, it.trackingNumber, it.ticketNumber, it.status) }
    }

    @Throws(Throwable::class)
    suspend fun addShippingOrder(customer: String, items: String?, address: String?, ticket: String?): Boolean = withContext(io) {
        if (currentUser?.canAdjustStock != true || customer.isBlank()) return@withContext false
        db.shippingOrderQueries.insert(customer, items, address, "", ticket, Strings.SHIP_PENDING)
        audit("ADD_SHIPPING", Strings.auditShipCreated(customer))
        true
    }

    @Throws(Throwable::class)
    suspend fun updateShippingOrder(o: ShippingOrder): Boolean = withContext(io) {
        if (currentUser?.canAdjustStock != true) return@withContext false
        db.shippingOrderQueries.update(o.customerName, o.itemsSummary, o.shippingAddress, o.trackingNumber, o.ticketNumber, o.status, o.id)
        audit("UPDATE_SHIPPING", Strings.auditShipUpdated(o.id.toString()))
        true
    }

    @Throws(Throwable::class)
    suspend fun deleteShippingOrder(o: ShippingOrder): Boolean = withContext(io) {
        if (!isAdmin) return@withContext false
        db.shippingOrderQueries.delete(o.id)
        audit("DELETE_SHIPPING", Strings.auditShipDeleted(o.id.toString()))
        true
    }

    // =========================================================== IA

    /** Analyse heuristique "IA Optimisation" (MainController.generateAIInsights). */
    @Throws(Throwable::class)
    suspend fun generateAIInsights(): List<AIInsight> = withContext(io) {
        val products = db.productQueries.getAll().executeAsList().map { it.toModel() }
        val movements = db.stockMovementQueries.getAll().executeAsList().map { it.toModel() }
        val out = mutableListOf<AIInsight>()
        for (p in products) {
            val mine = movements.filter { it.productId == p.id }
            val monthlyOut = mine.filter { it.type == "OUT" }.sumOf { it.quantity }
            if (p.quantity < monthlyOut) {
                out += AIInsight(Strings.insightRecommendation(p.name), Strings.insightRecommendationDesc(monthlyOut), InsightType.RECOMMENDATION, "HIGH")
            }
            mine.filter { it.quantity > 20 }.forEach {
                out += AIInsight(Strings.INSIGHT_ANOMALY, Strings.insightAnomalyDesc(it.quantity, p.name), InsightType.ANOMALY, "MEDIUM")
            }
            if (mine.isEmpty() && p.quantity > 0) {
                out += AIInsight(Strings.insightDeadStock(p.name), Strings.INSIGHT_DEAD_STOCK_DESC, InsightType.PRICE_SUGGESTION, "LOW")
            }
            if (!p.expirationDate.isNullOrEmpty()) {
                out += AIInsight(Strings.INSIGHT_RISK, Strings.insightRiskDesc(p.name, p.expirationDate), InsightType.RISK, "HIGH")
            }
        }
        if (out.isEmpty()) out += AIInsight(Strings.INSIGHT_DONE, Strings.INSIGHT_NO_ALERTS, InsightType.PREDICTION, "LOW")
        out
    }

    /** Assistant IA (AIChatActivity). */
    @Throws(Throwable::class)
    suspend fun askAssistant(question: String): String {
        if (!StockItConfig.isAIConfigured) {
            return OfflineAI.answer(question, getStock(), getReportData(), generateAIInsights())
        }
        val r = AIGateway.ask(Strings.AI_SYSTEM_ROLE + "\n\n" + stockContext() + "\n\nQuestion: " + question)
        return r.text?.let { plainText(it) } ?: (Strings.AI_ERROR + "\n\n" + OfflineAI.answer(question, getStock(), getReportData(), generateAIInsights()))
    }

    /** Contexte envoyé à Claude : état réel du stock (pour répondre avec les vrais chiffres). */
    private suspend fun stockContext(): String {
        val d = getReportData()
        val products = getStock()
        val recent = getStockMovements().take(15)
        return buildString {
            append("Données actuelles du stock IT (base StockIT) :\n")
            append("- Total : ${d.totalQuantity} articles, valeur ${formatMoney(d.totalValue)} €, ")
            append("${d.outOfStockCount} en rupture, ${d.lowStockCount} en stock bas.\n")
            append("Produits (nom | catégorie | quantité | seuil | prix unitaire) :\n")
            products.take(150).forEach {
                append("- ${it.name} | ${it.category ?: "-"} | ${it.quantity} | ${it.minThreshold} | ${formatMoney(it.unitPrice)} €\n")
            }
            if (recent.isNotEmpty()) {
                append("Derniers mouvements :\n")
                recent.forEach { append("- ${it.date} ${it.type} ${it.quantity} x ${it.productName} (${it.reason ?: ""})\n") }
            }
            append(tr("Réponds en français, de façon concise, en t'appuyant uniquement sur ces données. Pas de markdown.",
                "Answer in English, concisely, relying only on this data. No markdown."))
        }
    }

    /** Retire le markdown (**, #, `) que l'app ne sait pas afficher. */
    private fun plainText(s: String): String =
        s.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""(?m)^#{1,6}\s*"""), "")
            .replace("`", "")
            .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")
            .trim()

    /** Rapport mensuel rédigé par l'IA (texte du PDF). */
    @Throws(Throwable::class)
    suspend fun generateAIReport(): String {
        val d = getReportData()
        val raw = "Inventaire Vistaprint : ${d.totalQuantity} articles, ${d.outOfStockCount} en rupture, " +
            "${d.lowStockCount} stock bas. Valeur : ${formatMoney(d.totalValue)} euros."
        if (!StockItConfig.isAIConfigured) {
            return OfflineAI.monthlyReport(getStock(), d, generateAIInsights())
        }
        val prompt = "Agis comme un gestionnaire de stock IT expert pour Vistaprint. Voici les données brutes du mois : " + raw +
            ". Rédige un rapport de synthèse professionnel en français (max 150 mots). Analyse les tendances, signale les alertes critiques et donne des recommandations. Utilise un ton pro mais concis."
        val lang = tr(" Rédige en français.", " Write the report IN ENGLISH.")
        val txt = AIGateway.ask(prompt + lang + " N'utilise pas de markdown (ni #, ni **).").text?.let { plainText(it) }
        return txt ?: OfflineAI.monthlyReport(getStock(), d, generateAIInsights())
    }

    // =========================================================== IA — QUALITÉ / PRÉVISION / COMMANDES

    /** Trace la décision de l'utilisateur après un scan (pour mesurer la qualité de la reconnaissance). */
    @Throws(Throwable::class)
    suspend fun recordVisionFeedback(detected: String?, finalName: String, confidence: Int, serialRead: Boolean): Unit =
        withContext(io) {
            audit(AIQualityCalculator.AUDIT_VISION, AIQualityCalculator.visionAuditDetail(detected, finalName, confidence, serialRead))
        }

    /** Indicateurs de qualité IA (tickets + vision), calculés sur les données réelles de l'app. */
    @Throws(Throwable::class)
    suspend fun getAIQuality(): AIQuality = withContext(io) {
        val tickets = db.analyzedTicketQueries.getAll().executeAsList().map {
            AnalyzedTicket(it.id, it.ticketId, it.equipmentKey, it.equipmentName, it.suggestedQty.toInt(), it.deliveredQty.toInt(),
                it.reason, it.fulfilled, it.analyzedAt, it.fulfilledAt, it.jiraUpdated, it.ticketSummary)
        }
        val audit = db.auditLogQueries.getAll().executeAsList()
            .filter { it.action == AIQualityCalculator.AUDIT_VISION }
            .map { AuditLog(it.id, it.userId, it.action, it.details, it.timestamp, it.ipAddress, it.device) }
        AIQualityCalculator.compute(tickets, audit)
    }

    /**
     * Prévision des besoins : consommation réelle (sorties des 60 derniers jours) + demande en attente
     * dans les tickets Jira ouverts (si Jira est configuré), puis ajustement / commentaire par Claude.
     */
    @Throws(Throwable::class)
    suspend fun getDemandForecast(useAI: Boolean = true): List<Forecast> {
        val products = getStock()
        val (outs, leadTimes) = withContext(io) {
            val outs = db.stockMovementQueries.getAll().executeAsList()
                .filter { it.type == "OUT" && it.createdAt > 0 }
                .map { UsagePoint(it.productId, it.quantity.toInt(), it.createdAt) }
            val suppliers = db.supplierQueries.getAll().executeAsList()
                .map { Supplier(it.id, it.name, it.email, it.phone, it.address, it.leadTime.toInt(), it.discount) }
            val orders = db.purchaseOrderQueries.getAll().executeAsList()
                .map { PurchaseOrder(it.id, it.productName, it.quantity.toInt(), it.supplier, it.date, it.status) }
            outs to products.associate { p -> p.id to (OrderDrafter.usualSupplier(p, orders, suppliers)?.leadTime ?: DemandForecaster.DEFAULT_LEAD_TIME) }
        }
        // Demande Jira en attente : 1 unité par ticket ouvert qui mentionne le type d'équipement
        // et n'a pas encore été servi pour ce produit (cache analyzed_tickets).
        val tickets = if (StockItConfig.isJiraConfigured) runCatching { JiraClient.loadOpenTickets(50).tickets }.getOrNull().orEmpty() else emptyList()
        val pending = mutableMapOf<Long, Int>()
        if (tickets.isNotEmpty()) {
            val served = withContext(io) { db.analyzedTicketQueries.getAll().executeAsList().filter { it.deliveredQty > 0 } }
            for (p in products) {
                val key = equipmentKeyOf(p.name)
                val n = tickets.count { t ->
                    ProductMatcher.mentionsSameType((t.summary ?: "") + " " + (t.description ?: "").take(300), p.name) &&
                        served.none { it.ticketId == t.key && it.equipmentKey == key }
                }
                if (n > 0) pending[p.id] = n
            }
        }
        val base = DemandForecaster.forecast(products, outs, pending, leadTimes, nowMs())
        if (!useAI || !StockItConfig.isAIConfigured || base.isEmpty()) return base
        val focus = base.filter { it.risk != "OK" || it.dailyUsage > 0 || it.pendingDemand > 0 }.ifEmpty { base.take(10) }
        val r = AIGateway.cimpressChat(DemandForecaster.buildPrompt(focus, tickets.mapNotNull { it.summary }), maxTokens = 1200)
        return DemandForecaster.applyClaude(base, r.text)
    }

    /** Brouillon de demande d'achat pour un produit (fournisseur habituel + quantité + justification Claude). */
    @Throws(Throwable::class)
    suspend fun draftPurchaseOrder(f: Forecast): OrderDraft {
        val (product, supplier) = withContext(io) {
            val p = db.productQueries.getById(f.productId).executeAsOneOrNull()?.toModel()
                ?: Product(id = f.productId, name = f.productName, quantity = f.stock, minThreshold = f.minThreshold)
            val suppliers = db.supplierQueries.getAll().executeAsList()
                .map { Supplier(it.id, it.name, it.email, it.phone, it.address, it.leadTime.toInt(), it.discount) }
            val orders = db.purchaseOrderQueries.getAll().executeAsList()
                .map { PurchaseOrder(it.id, it.productName, it.quantity.toInt(), it.supplier, it.date, it.status) }
            p to OrderDrafter.usualSupplier(p, orders, suppliers)
        }
        val local = OrderDrafter.localDraft(f, product, supplier)
        if (!StockItConfig.isAIConfigured) return local
        val r = AIGateway.cimpressChat(OrderDrafter.buildPrompt(local, f), maxTokens = 250)
        val txt = r.text?.let { plainText(it) }?.takeIf { it.isNotBlank() } ?: return local
        return local.copy(justification = txt.take(500), byAI = true)
    }

    /**
     * Validation manager d'un brouillon : création du bon de commande local (« En attente »).
     * Retourne la référence « PO#<id> ». ADMIN / MANAGER uniquement.
     */
    @Throws(Throwable::class)
    suspend fun approveOrderDraft(d: OrderDraft): OpResult {
        if (!canEditStock) return OpResult(false, tr("Permission refusée : rôle ADMIN ou MANAGER requis", "Permission denied: ADMIN or MANAGER role required"))
        if (d.quantity <= 0) return OpResult(false, tr("Quantité invalide", "Invalid quantity"))
        val id = withContext(io) {
            db.transactionWithResult {
                db.purchaseOrderQueries.insert(d.productName, d.quantity.toLong(), d.supplier, today("dd/MM/yyyy"), Strings.PURCHASE_PENDING)
                db.purchaseOrderQueries.lastInsertId().executeAsOne()
            }
        }
        val ref = "PO#$id"
        withContext(io) {
            audit("AI_ORDER_APPROVED", "${d.productName} x${d.quantity} — ${d.supplier ?: "fournisseur ?"} — $ref" +
                if (d.byAI) " [justification IA]" else "")
        }
        background.launch {
            SlackNotifier.send(":shopping_trolley: [StockIT] Commande validée : ${d.productName} x${d.quantity}" +
                " (${d.supplier ?: "fournisseur à définir"}) — $ref\n> ${d.justification.take(200)}")
        }
        return OpResult(true, ref)
    }

    // =========================================================== RÉCLAMATIONS

    /** Local d'abord ; `syncRemote=true` tente ensuite la synchro serveur. */
    @Throws(Throwable::class)
    suspend fun getClaims(syncRemote: Boolean = true): List<Claim> {
        if (syncRemote) {
            runCatching { StockItApi.getClaims() }.getOrNull()?.let { remote ->
                withContext(io) {
                    remote.forEach { c ->
                        db.claimQueries.upsert(c.id.takeIf { it > 0 }, c.subject, c.description, c.senderName, c.priority, c.status, c.timestamp)
                    }
                }
            }
        }
        return withContext(io) {
            val u = currentUser
            when {
                isAdmin -> db.claimQueries.getAll().executeAsList()
                u != null -> db.claimQueries.getByUser(u.username).executeAsList()
                else -> emptyList()
            }.map { Claim(it.id, it.subject, it.description, it.senderName, it.priority, it.status, it.timestamp) }
        }
    }

    @Throws(Throwable::class)
    suspend fun addClaim(subject: String, desc: String?, priority: String) {
        if (currentUser == null || subject.isBlank()) return
        val claim = Claim(
            subject = subject, description = desc,
            senderName = currentUser?.username ?: Strings.CLAIMS_ANONYMOUS,
            priority = priority, status = "OUVERT", timestamp = nowMs(),
        )
        withContext(io) {
            db.claimQueries.insert(claim.subject, claim.description, claim.senderName, claim.priority, claim.status, claim.timestamp)
        }
        background.launch { runCatching { StockItApi.addClaim(claim) } }
    }

    @Throws(Throwable::class)
    suspend fun getMessages(claimId: Long): List<ChatMessage> =
        runCatching { StockItApi.getMessages(claimId) }.getOrNull() ?: emptyList()

    @Throws(Throwable::class)
    suspend fun addMessage(claimId: Long, text: String): Boolean {
        val uid = currentUser?.username ?: return false
        if (text.isBlank()) return false
        return runCatching { StockItApi.addMessage(claimId, ChatMessage(uid, uid, text, nowMs())) }.getOrDefault(false)
    }

    // =========================================================== ALERTES

    /**
     * Alerte stock bas (bouton "ALERTE N8N / SLACK") : backend /alerts + Slack bot +
     * ticket Jira + e-mail n8n. Retourne le résultat de la création Jira.
     */
    @Throws(Throwable::class)
    suspend fun triggerLowStockAlert(p: Product): OpResult {
        if (currentUser == null) return OpResult(false, "not_signed_in")
        val now = nowMs()
        val last = lastAlertAt[p.id]
        if (last != null && now - last < ALERT_COOLDOWN_MS) return OpResult(false, "rate_limited")
        lastAlertAt[p.id] = now
        val data = buildJsonObject {
            put("event", "LOW_STOCK_ALERT")
            put("productName", p.name)
            put("currentQty", p.quantity)
            put("threshold", p.minThreshold)
            put("user", actorName)
            put("timestamp", nowMs())
            put("email_to", StockItConfig.defaultRecipientEmail)
        }
        background.launch { runCatching { StockItApi.sendAlert(data) } }
        background.launch { SlackNotifier.postAsBot("🚨 *ALERTE STOCK BAS* 🚨\nProduit: ${p.name}\nQuantité actuelle: ${p.quantity}") }
        background.launch {
            StockItReporter.sendEvent(
                "Alerte stock bas : ${p.name}",
                "🚨 Le produit \"${p.name}\" est passé sous le seuil critique.\n\n" +
                    "• Quantité restante : ${p.quantity}\n" +
                    "• Seuil configuré  : ${p.minThreshold}\n\n" +
                    "👉 Merci de déclencher une commande fournisseur avant rupture.",
                StockItConfig.defaultRecipientEmail,
            )
        }
        val token = StockItConfig.jiraApiToken
        if (token.isBlank() || token == "YOUR_JIRA_TOKEN_HERE") return OpResult(false, tr("Jira non configuré", "Jira not configured"))
        return JiraClient.createTask(
            StockItConfig.jiraProjectKey,
            "[StockIT] Alerte Stock: ${p.name}",
            "Alerte automatique : la quantité de ${p.name} est descendue à ${p.quantity}.",
        )
    }

    // =========================================================== GAMIFICATION

    private fun addPointsInternal(points: Int) {
        var u = currentUser ?: return
        u = u.copy(points = u.points + points)
        val newLevel = u.points / 100 + 1
        if (newLevel > u.level) {
            u = u.copy(level = newLevel)
            audit("LEVEL_UP", "Niveau atteint: $newLevel")
        }
        db.questQueries.getActive().executeAsList().forEach { q ->
            val count = q.currentCount + 1
            if (count >= q.goalCount) {
                db.questQueries.update(count, true, q.id)
                u = u.copy(points = u.points + q.pointReward.toInt())
                val badge = q.badgeReward
                if (!badge.isNullOrEmpty()) u = u.copy(badges = if (u.badges.isEmpty()) badge else u.badges + "," + badge)
                audit("QUEST_DONE", "Quête terminée: ${q.title}")
            } else {
                db.questQueries.update(count, false, q.id)
            }
        }
        if (u.id != 0L) updateUserRow(u)
        currentUser = u
    }

    @Throws(Throwable::class)
    suspend fun getQuests(): List<Quest> = withContext(io) {
        if (db.questQueries.count().executeAsOne() == 0L) {
            db.questQueries.insert("Pionnier du Stock", "Ajoutez 5 produits au stock", 5, 0, 50, "Badge Bronze", false)
            db.questQueries.insert("Inspecteur Expert", "Scannez 10 objets avec l'IA", 10, 0, 100, "Badge Argent", false)
        }
        db.questQueries.getActive().executeAsList().map {
            Quest(it.id, it.title, it.description, it.goalCount.toInt(), it.currentCount.toInt(), it.pointReward.toInt(), it.badgeReward, it.isCompleted)
        }
    }

    // =========================================================== interne

    private fun insertMovement(productId: Long, productName: String?, type: String, qty: Int, reason: String?, comment: String?) {
        db.stockMovementQueries.insert(
            productId, productName, type, qty.toLong(), today("dd/MM/yyyy HH:mm"), reason, actorName, comment, null, null, nowMs(),
        )
        notifyStockEvent(productId, productName, type, qty, reason, null)
    }

    /**
     * Automatisations n8n à chaque mouvement de stock :
     *  - `stockit/event`        (entrée / sortie)
     *  - `stockit/asset-status` (IN_STOCK / OUT)
     * En cas d'échec (réseau, workflow inactif), l'appel est mis en file d'attente
     * et renvoyé automatiquement (cf. [flushOutbox]).
     */
    private fun notifyStockEvent(productId: Long, productName: String?, type: String, qty: Int, reason: String?, ticketId: String?) {
        if (qty <= 0) return
        val tag = db.productQueries.getById(productId).executeAsOneOrNull()?.assetTag?.takeIf { it.isNotBlank() } ?: "ID-$productId"
        val ts = formatEpoch(nowMs(), "yyyy-MM-dd'T'HH:mm:ssZ")
        val s = StockIt.sessionOrNull
        val calls = mutableListOf<Pair<String, String>>()
        val eventUrl = StockItConfig.stockitEventWebhookUrl
        if (eventUrl.isNotBlank()) {
            calls += eventUrl to buildJsonObject {
                put("barcode", tag); put("product", productName ?: "?"); put("qty", qty); put("type", type)
                put("reason", reason ?: ""); put("ticket_id", ticketId ?: ""); put("timestamp", ts)
                put("user", s?.username ?: "stockit-system")
                put("user_email", s?.email?.takeIf { it.isNotBlank() } ?: StockItConfig.defaultRecipientEmail)
                put("source", "ios"); put("event_id", randomId())
            }.toString()
        }
        val assetUrl = StockItConfig.stockitAssetStatusWebhookUrl
        if (assetUrl.isNotBlank()) {
            calls += assetUrl to StockItReporter.assetStatusBody(
                tag, if (type == "IN") "IN_STOCK" else "OUT", reason, productName, qty, ticketId, ts,
            )
        }
        background.launch {
            for ((url, body) in calls) {
                val r = StockItReporter.postJson(url, body)
                if (!r.success) withContext(io) { db.outboxQueries.insert(url, body, r.detail, nowMs(), nowMs() + backoffMs(1)) }
            }
        }
    }

    /**
     * Renvoie les appels webhook en attente (utilisateur connecté requis). Les éléments en échec
     * attendent un délai croissant ; le secret n'est jamais stocké (ajouté et signé à l'envoi).
     * Retourne le nombre restant.
     */
    @Throws(Throwable::class)
    suspend fun flushOutbox(): Long {
        if (currentUser == null) return withContext(io) { db.outboxQueries.count().executeAsOne() }
        val items = withContext(io) {
            sanitizeOutbox()
            db.outboxQueries.pending(nowMs()).executeAsList()
        }
        for (it in items) {
            val r = StockItReporter.postJson(it.url, it.body)
            withContext(io) {
                if (r.success) db.outboxQueries.delete(it.id)
                else db.outboxQueries.markFailed(r.detail?.take(200), nowMs() + backoffMs(it.attempts + 1), it.id)
            }
        }
        return withContext(io) { db.outboxQueries.count().executeAsOne() }
    }

    /** 30 s, 1 min, 2 min… plafonné à 6 h. */
    private fun backoffMs(attempts: Long): Long = (30_000L shl (attempts - 1).coerceIn(0, 10).toInt()).coerceAtMost(6 * 3_600_000L)

    /** Anciennes versions : le secret partagé était enregistré dans le corps des appels en attente. */
    private fun sanitizeOutbox() {
        for (row in db.outboxQueries.all().executeAsList()) {
            if (!row.body.contains("\"secret\"")) continue
            val clean = runCatching {
                val o = Http.json.parseToJsonElement(row.body).jsonObject
                JsonObject(o - "secret").toString()
            }.getOrNull()
            if (clean != null) db.outboxQueries.setBody(clean, row.id) else db.outboxQueries.delete(row.id)
        }
    }

    /** Nombre d'appels webhook en attente (affiché dans Paramètres). */
    @Throws(Throwable::class)
    suspend fun pendingWebhookCount(): Long = withContext(io) { db.outboxQueries.count().executeAsOne() }

    /**
     * Journal d'audit chaîné : hash = SHA-256(hash précédent | utilisateur | action | détails | horodatage | appareil).
     * Pas d'adresse IP inventée : l'appareil est l'information fiable disponible côté client.
     */
    private fun audit(action: String, details: String, actor: String? = null) {
        val user = actor ?: currentUser?.username ?: Strings.USER_GUEST
        val ts = nowMs()
        val prev = db.auditLogQueries.lastHash().executeAsOneOrNull() ?: GENESIS_HASH
        db.auditLogQueries.insert(user, action, details, ts, null, deviceName, auditHash(prev, user, action, details, ts, deviceName))
    }

    private fun auditHash(prev: String, user: String?, action: String, details: String?, ts: Long, device: String?): String =
        Sha256.digest(listOf(prev, user.orEmpty(), action, details.orEmpty(), ts.toString(), device.orEmpty())
            .joinToString("\u001f").encodeToByteArray()).toHex()

    /**
     * Vérifie la chaîne du journal : false si une entrée a été modifiée, supprimée ou insérée hors application.
     * (Les entrées antérieures à la mise à jour, sans hash, ne sont pas couvertes.)
     */
    @Throws(Throwable::class)
    suspend fun verifyAuditChain(): Boolean = withContext(io) {
        var prev = GENESIS_HASH
        for (row in db.auditLogQueries.chain().executeAsList()) {
            val expected = auditHash(prev, row.userId, row.action, row.details, row.timestamp, row.device)
            if (expected != row.hash) return@withContext false
            prev = expected
        }
        true
    }

    private fun updateUserRow(u: User) {
        db.userQueries.updateProfile(u.role, u.points.toLong(), u.level.toLong(), u.badges, u.id)
    }

    private fun com.example.stockit.shared.db.Users.toModel() =
        User(id, username, Roles.normalize(role), points.toInt(), level.toInt(), badges, provider)

    private fun com.example.stockit.shared.db.Products.toModel() = Product(
        id = id, name = name ?: "", category = category, categoryId = categoryId, description = description,
        assetTag = assetTag, quantity = quantity.toInt(), unitPrice = unitPrice, manufacturingDate = manufacturingDate,
        expirationDate = expirationDate, minThreshold = minThreshold.toInt(), poNumber = poNumber,
        poDescription = poDescription, receivedFrom = receivedFrom, articleNumber = articleNumber, brand = brand,
        packagePoNumber = packagePoNumber,
    )

    private fun com.example.stockit.shared.db.Stock_movements.toModel() = StockMovement(
        id, productId, productName, type, quantity.toInt(), date, reason, userName, comment, ticketId, assignmentReason,
    )

    private companion object {
        const val MAX_FAILED_LOGINS = 5
        const val LOCKOUT_WINDOW_MS = 5 * 60_000L
        const val ALERT_COOLDOWN_MS = 10 * 60_000L
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    /** Dernière alerte stock bas envoyée, par produit (anti-flood Slack / Jira). */
    private val lastAlertAt = mutableMapOf<Long, Long>()
}

/** "1234.5" → "1234.50" (sans dépendance à String.format, absent en Kotlin/Native). */
fun formatMoney(v: Double): String {
    val cents = kotlin.math.round(v * 100).toLong()
    val abs = kotlin.math.abs(cents)
    val s = "${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    return if (cents < 0) "-$s" else s
}
