package com.example.stockit.util

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.stockit.controller.MainController
import com.example.stockit.model.AppDatabase
import com.example.stockit.model.AuditLog
import com.example.stockit.model.Product
import com.example.stockit.shared.ai.AssetGrouping
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.OpResult
import com.example.stockit.shared.network.JiraAssetsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * StockIT — Synchronisation Jira Assets ↔ inventaire Room (identique iOS StockItRepository).
 *
 *  - [sync] : Jira Assets = source de vérité. Les unités « In stock » sont regroupées par modèle
 *    ([AssetGrouping]) puis l'inventaire local est aligné (création / mise à jour / quantité 0,
 *    suppression des produits de démonstration). Les produits créés à la main qui n'existent
 *    pas encore dans Jira y sont ensuite envoyés.
 *  - [onQuantityChanged] / [onProductEdited] / [onProductDeleted] : répercutent dans Jira Assets
 *    les modifications faites dans l'app (+/−, édition, suppression → « Inactive »).
 *
 * Aucune opération ne bloque l'action locale : le résultat est tracé dans l'audit.
 */
object AssetsSync {

    /** Préfixe du champ assetTag des produits liés à Jira Assets (clé de regroupement). */
    const val ASSETS_TAG = "JA:"

    /** Produits de démonstration (supprimés à la 1re synchro). */
    private val DEMO_TAGS = setOf("DELL-LAT-01", "LOGI-MX-02", "HP-SCR-24")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    /** Clés Jira (SIT-…) des unités « In stock » de chaque produit synchronisé (dernière synchro). */
    private val keysByTag = ConcurrentHashMap<String, List<String>>()
    private val serialsByTag = ConcurrentHashMap<String, List<String?>>()
    private val typeByTag = ConcurrentHashMap<String, String>()

    /** Date (ms) de la dernière synchronisation réussie, 0 si jamais. */
    @JvmStatic @Volatile var lastSyncMs: Long = 0
        private set

    @JvmStatic @Volatile var isSyncing: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<Runnable>()

    data class Report(
        @JvmField val success: Boolean,
        @JvmField val error: String?,
        @JvmField val products: Int,
        @JvmField val units: Int,
        @JvmField val created: Int,
        @JvmField val updated: Int,
        @JvmField val removed: Int,
        @JvmField val demoRemoved: Int,
    )

    fun interface ReportCallback { fun onResult(r: Report) }

    @JvmStatic
    fun init(context: Context) { app = context.applicationContext }

    @JvmStatic fun isConfigured(): Boolean = JiraAssetsClient.isConfigured

    /** true si le produit vient de Jira Assets (sa quantité est pilotée par Jira). */
    @JvmStatic fun isSynced(p: Product?): Boolean = p?.assetTag?.startsWith(ASSETS_TAG) == true

    /** Écouteurs notifiés (thread principal) au début / à la fin d'une synchro. */
    @JvmStatic fun addListener(r: Runnable) { listeners += r }
    @JvmStatic fun removeListener(r: Runnable) { listeners -= r }
    private fun notifyListeners() = main.post { listeners.forEach { it.run() } }

    private fun db() = AppDatabase.getInstance(app)

    // =========================================================== JIRA ASSETS → INVENTAIRE

    /** Lance une synchronisation. cb (thread principal) peut être null. Ignoré si déjà en cours. */
    @JvmStatic
    fun sync(cb: ReportCallback?) {
        if (!::app.isInitialized || !isConfigured() || isSyncing) {
            val why = if (isSyncing) "sync_in_progress" else "assets_not_configured"
            cb?.let { c -> main.post { c.onResult(Report(false, why, 0, 0, 0, 0, 0, 0)) } }
            return
        }
        isSyncing = true
        notifyListeners()
        scope.launch {
            val r = runCatching { syncInternal() }
                .getOrElse { Report(false, "${it::class.simpleName}: ${it.message}", 0, 0, 0, 0, 0, 0) }
            if (r.success) lastSyncMs = System.currentTimeMillis()
            isSyncing = false
            main.post {
                cb?.onResult(r)
                listeners.forEach { it.run() }
            }
        }
    }

    private suspend fun syncInternal(): Report {
        val fetched = JiraAssetsClient.fetchAll()
        val units = fetched.units ?: return Report(false, fetched.error, 0, 0, 0, 0, 0, 0)
        val groups = AssetGrouping.group(units)
        val db = db()
        var created = 0; var updated = 0; var removed = 0; var demo = 0
        db.runInTransaction(Runnable {
            val dao = db.productDao()
            val all = dao.all
            all.filter { it.assetTag in DEMO_TAGS }.forEach { dao.delete(it); demo++ }
            val synced = all.filter { isSynced(it) }.associateBy { it.assetTag!! }
            // Produits locaux non liés (ex. créés par un scan) : rattachés à leur modèle Jira s'ils correspondent.
            val unlinked = all.filter { p ->
                p.assetTag !in DEMO_TAGS && !isSynced(p) && p.category != "Batch"
            }.toMutableList()
            val seen = mutableSetOf<String>()
            keysByTag.clear(); serialsByTag.clear(); typeByTag.clear()
            for (g in groups) {
                val tag = ASSETS_TAG + g.key
                seen += tag
                keysByTag[tag] = g.objectKeys
                serialsByTag[tag] = g.unitSerials
                typeByTag[tag] = g.typeId
                val category = categoryForType(g.typeId)
                val serialNote = if (g.serials.isEmpty()) null else "S/N : " + g.serials.take(20).joinToString(", ") +
                    (if (g.serials.size > 20) " (+${g.serials.size - 20})" else "")
                val existing = synced[tag] ?: unlinked.firstOrNull {
                    AssetGrouping.canonical(it.name ?: "") == AssetGrouping.canonical(g.name)
                }?.also { unlinked.remove(it) }
                if (existing != null) {
                    val changed = existing.quantity != g.quantity || existing.name != g.name
                    existing.name = g.name
                    existing.category = category
                    if (serialNote != null) existing.description = serialNote
                    existing.assetTag = tag
                    existing.quantity = g.quantity
                    g.poNumber?.let { existing.poNumber = it }
                    g.vendor?.let { existing.receivedFrom = it }
                    g.brand?.let { existing.brand = it }
                    dao.update(existing)
                    if (changed) updated++
                } else {
                    val p = Product(g.name, category, serialNote, tag, g.quantity, 0.0, null, null)
                    p.poNumber = g.poNumber
                    p.receivedFrom = g.vendor
                    p.brand = g.brand
                    dao.insert(p)
                    created++
                }
            }
            // Modèles synchronisés qui n'ont plus d'unité « In stock » dans Jira → 0 (historique conservé).
            synced.values.filter { it.assetTag !in seen && it.quantity != 0 }.forEach {
                it.quantity = 0; dao.update(it); removed++
            }
            audit("ASSETS_SYNC", "Jira Assets → inventaire : ${groups.size} modèles, ${groups.sumOf { it.quantity }} unités " +
                "(+$created, ~$updated, 0:$removed, démo:$demo)")
        })
        // Produits créés à la main (ou hors ligne) qui n'existent pas encore dans Jira → envoyés.
        val pushed = pushLocalOnlyProducts()
        return Report(true, null, groups.size + pushed, groups.sumOf { it.quantity }, created + pushed, updated, removed, demo)
    }

    /**
     * Envoie à Jira Assets les produits locaux non liés (quantité > 0), puis les lie (assetTag JA:…)
     * pour qu'ils ne soient jamais envoyés deux fois. Les doublons locaux sont fusionnés avant l'envoi.
     */
    private suspend fun pushLocalOnlyProducts(): Int {
        if (!isConfigured() || StockItConfig.testMode) return 0
        val dao = db().productDao()
        val local = dao.all.filter {
            !isSynced(it) && it.assetTag !in DEMO_TAGS && it.quantity > 0 && !it.name.isNullOrBlank()
        }
        if (local.isEmpty()) return 0
        var pushed = 0
        for ((_, dups) in local.groupBy { AssetGrouping.canonical(it.name ?: "") }) {
            val keep = dups.first()
            val qty = dups.sumOf { it.quantity }
            if (dups.size > 1) {
                db().runInTransaction(Runnable {
                    keep.quantity = qty
                    dao.update(keep)
                    dups.drop(1).forEach { dao.delete(it) }
                })
                audit("MERGE_DUPLICATES", "${keep.name} : ${dups.size} fiches fusionnées (qté $qty)")
            }
            val r = JiraAssetsClient.checkIn(keep.name ?: "", qty, keep.brand, keep.articleNumber, keep.poNumber, keep.receivedFrom, emptyList())
            audit(if (r.success) "ASSETS_PUSH" else "ASSETS_PUSH_FAILED", "${keep.name} x$qty → ${r.detail ?: ""}".take(400))
            if (r.success) {
                dao.getById(keep.id)?.let { it.assetTag = localTag(it.name ?: "", it.brand); dao.update(it) }
                pushed++
            }
        }
        return pushed
    }

    private fun categoryForType(typeId: String): String = when (typeId) {
        StockItConfig.jiraAssetsTypeComputers -> "Informatique"
        StockItConfig.jiraAssetsTypeMonitors -> "Écran"
        else -> "Périphérique"
    }

    private fun localTag(name: String, brand: String?): String {
        val typeId = JiraAssetsClient.typeIdFor(JiraAssetsClient.kindFor(name))
        return ASSETS_TAG + AssetGrouping.keyForLocal(typeId, name, brand)
    }

    // =========================================================== INVENTAIRE → JIRA ASSETS

    /**
     * Quantité modifiée dans l'app (+/−, ajout manuel, rafale) → Jira Assets.
     * [delta] > 0 : création d'unités « In stock » ; < 0 : unités passées à OUT.
     */
    @JvmStatic
    fun onQuantityChanged(productId: Int, delta: Int, reason: String) {
        if (!::app.isInitialized || !isConfigured() || delta == 0) return
        scope.launch {
            val p = db().productDao().getById(productId) ?: return@launch
            runCatching { pushDelta(p, delta, reason) }
        }
    }

    /** Produit modifié (formulaire) : renommage + écart de quantité. [old] = état avant modification. */
    @JvmStatic
    fun onProductEdited(old: Product?, updated: Product) {
        if (!::app.isInitialized || !isConfigured() || old == null) return
        val oldSnapshot = copyOf(old)
        val newName = updated.name ?: ""
        val newBrand = updated.brand
        val delta = updated.quantity - old.quantity
        scope.launch {
            runCatching {
                if (oldSnapshot.name != newName || oldSnapshot.brand != newBrand) renameInAssets(oldSnapshot, newName, newBrand)
                if (delta != 0) db().productDao().getById(updated.id)?.let {
                    pushDelta(it, delta, if (delta > 0) "ADJUST_IN" else "ADJUST_OUT")
                }
            }
        }
    }

    /** Produit supprimé : ses unités en stock passent à « Inactive » (aucune suppression dans la CMDB). */
    @JvmStatic
    fun onProductDeleted(p: Product) {
        if (!::app.isInitialized || !isConfigured() || !isSynced(p) || p.quantity <= 0) return
        val snapshot = copyOf(p)
        scope.launch { runCatching { pushDelta(snapshot, -snapshot.quantity, "DELETE", inactive = true) } }
    }

    private suspend fun pushDelta(p: Product, delta: Int, reason: String, inactive: Boolean = false): OpResult {
        val name = p.name ?: ""
        val r = if (delta > 0) {
            JiraAssetsClient.checkIn(name, delta, p.brand, p.articleNumber, p.poNumber, p.receivedFrom, emptyList())
        } else {
            val tag = p.assetTag?.takeIf { it.startsWith(ASSETS_TAG) }
            val keys = tag?.let { keysByTag[it] }.orEmpty()
            val actor = actorName()
            if (inactive) JiraAssetsClient.checkOut(name, -delta, actor, null, keys, tag?.let { typeByTag[it] },
                JiraAssetsClient.STATUS_INACTIVE, JiraAssetsClient.SUB_STATUS_DELETED)
            else JiraAssetsClient.checkOut(name, -delta, actor, null, keys, tag?.let { typeByTag[it] })
        }
        audit(if (r.success) "ASSETS_$reason" else "ASSETS_${reason}_FAILED",
            "$name ${if (delta > 0) "+" else ""}$delta → ${r.detail ?: ""}".take(400))
        if (r.success && delta > 0 && !isSynced(p)) {
            val dao = db().productDao()
            dao.getById(p.id)?.let { it.assetTag = localTag(it.name ?: "", it.brand); dao.update(it) }
        }
        if (r.success && delta < 0) removeKeys(p.assetTag ?: "", r.detail)
        return r
    }

    /** Nom modifié dans l'app → objets Jira renommés (et clé de liaison mise à jour). */
    private suspend fun renameInAssets(old: Product, newName: String, newBrand: String?): OpResult? {
        val tag = old.assetTag?.takeIf { it.startsWith(ASSETS_TAG) } ?: return null
        val keys = keysByTag[tag].orEmpty()
        val serials = serialsByTag[tag].orEmpty()
        val typeId = typeByTag[tag] ?: JiraAssetsClient.typeIdFor(JiraAssetsClient.kindFor(newName))
        val r = if (keys.isEmpty()) OpResult(false, "no_asset_found")
        else JiraAssetsClient.rename(keys.mapIndexed { i, k -> k to serials.getOrNull(i) }, typeId, newName, newBrand)
        audit(if (r.success) "ASSETS_RENAME" else "ASSETS_RENAME_FAILED", "${old.name} → $newName : ${r.detail ?: ""}".take(400))
        if (r.success) {
            val newTag = ASSETS_TAG + AssetGrouping.keyForLocal(typeId, newName, newBrand)
            val dao = db().productDao()
            dao.getById(old.id)?.let { it.assetTag = newTag; dao.update(it) }
            keysByTag[newTag] = keys; serialsByTag[newTag] = serials; typeByTag[newTag] = typeId
        }
        return r
    }

    // =========================================================== SORTIE SUR TICKET

    /** Clés « In stock » connues (dernière synchro) du produit synchronisé portant ce nom. */
    @JvmStatic
    fun preferredKeysFor(name: String): Pair<String?, List<String>> {
        if (!::app.isInitialized) return null to emptyList()
        val tag = db().productDao().all.firstOrNull { it.name == name && isSynced(it) }?.assetTag
        return tag to (tag?.let { keysByTag[it] }.orEmpty())
    }

    @JvmStatic fun typeIdForTag(tag: String?): String? = tag?.let { typeByTag[it] }

    /** Les unités sorties ne sont plus « In stock » : retirées de la liste locale. */
    @JvmStatic
    fun removeKeys(tag: String, detail: String?) {
        if (tag.isEmpty()) return
        val out = detail.orEmpty().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        keysByTag[tag]?.let { keysByTag[tag] = it.filterNot { k -> k in out } }
    }

    // =========================================================== utilitaires

    private fun actorName(): String = MainController.getCurrentUser()?.username ?: "Système"

    private fun audit(action: String, details: String) {
        runCatching {
            db().auditLogDao().insert(AuditLog(actorName(), action, details, System.currentTimeMillis(), "127.0.0.1",
                Build.MANUFACTURER + " " + Build.MODEL))
        }
    }

    private fun copyOf(p: Product): Product = Product(p.name, p.category, p.description, p.assetTag, p.quantity,
        p.unitPrice, p.manufacturingDate, p.expirationDate).also {
        it.id = p.id; it.brand = p.brand; it.articleNumber = p.articleNumber; it.poNumber = p.poNumber
        it.receivedFrom = p.receivedFrom; it.minThreshold = p.minThreshold
    }
}
