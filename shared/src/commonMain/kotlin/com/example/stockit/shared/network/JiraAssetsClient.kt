package com.example.stockit.shared.network

import com.example.stockit.shared.tr
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.OpResult
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * StockIT — Synchronisation avec Jira Assets (CMDB, schéma « Stock IT Testing », clé SIT).
 *
 *  - Entrée en stock  → création d'un objet Assets par unité (Status « In stock »).
 *  - Sortie de stock  → les objets « In stock » correspondants passent à « OUT / Checked Out »,
 *                       avec l'utilisateur assigné (demandeur du ticket).
 *
 * API : https://api.atlassian.com/jsm/assets/workspace/{workspaceId}/v1 (Basic email + token Jira).
 * Les IDs d'attributs sont résolus par leur NOM (robuste si le schéma est recréé).
 */
object JiraAssetsClient {

    /** Types d'objets du schéma (IDs configurés dans .env.sh). */
    enum class Kind { PERIPHERAL, COMPUTER, MONITOR }

    const val STATUS_IN = "In stock"
    const val SUB_STATUS_IN = "In stock - Brand New"
    const val STATUS_OUT = "OUT"
    const val SUB_STATUS_OUT = "Checked Out"
    const val STATUS_INACTIVE = "Inactive"
    const val SUB_STATUS_DELETED = "Deleted in StockIT"

    /** Nombre maximum d'objets créés / sortis en une opération (évite d'inonder la CMDB). */
    const val MAX_UNITS = 50

    private var workspaceId: String? = null
    private val attributeCache = mutableMapOf<String, Map<String, String>>()

    val isConfigured: Boolean get() = StockItConfig.isJiraAssetsConfigured

    // ------------------------------------------------------------------ règles (pures, testées)

    /** Type d'objet Assets déduit du nom de l'équipement. */
    fun kindFor(name: String): Kind {
        val n = " " + normalize(name) + " "
        val monitor = listOf("ecran", "moniteur", "monitor", "display", "screen")
        val computer = listOf(
            "ordinateur", "laptop", "notebook", "portable", "desktop", "pc", "latitude", "thinkpad",
            "macbook", "elitebook", "optiplex", "precision", "pro max", "pro14", "pro 14", "imac", "mac mini",
        )
        return when {
            monitor.any { n.contains(" $it") } -> Kind.MONITOR
            computer.any { n.contains(" $it") } -> Kind.COMPUTER
            else -> Kind.PERIPHERAL
        }
    }

    /** Valeur de l'attribut « Asset Type » (alignée sur les objets existants du schéma). */
    fun assetTypeLabel(kind: Kind, name: String): String = when (kind) {
        Kind.MONITOR -> "Monitor"
        Kind.PERIPHERAL -> "Peripheral"
        Kind.COMPUTER -> {
            val n = " " + normalize(name) + " "
            if (listOf("laptop", "portable", "notebook", "latitude", "thinkpad", "macbook", "elitebook").any { n.contains(" $it") }) "Laptop" else "Desktop"
        }
    }

    fun typeIdFor(kind: Kind): String = when (kind) {
        Kind.PERIPHERAL -> StockItConfig.jiraAssetsTypePeripherals
        Kind.COMPUTER -> StockItConfig.jiraAssetsTypeComputers.ifBlank { StockItConfig.jiraAssetsTypePeripherals }
        Kind.MONITOR -> StockItConfig.jiraAssetsTypeMonitors.ifBlank { StockItConfig.jiraAssetsTypePeripherals }
    }

    /**
     * Libellé d'un objet : « <nom> - SN <série> » si la série de l'unité est connue
     * (convention du schéma), sinon « <nom> #001 » (lot) ou « <nom> » (unité seule).
     */
    fun objectLabel(name: String, index: Int, total: Int, serial: String?): String = when {
        !serial.isNullOrBlank() -> "$name - SN $serial"
        total == 1 -> name
        else -> "$name #" + (index + 1).toString().padStart(3, '0')
    }

    /** Série de l'unité [index] : la i-ème série lue, sinon null (jamais réutilisée pour deux unités). */
    fun serialFor(serials: List<String>, index: Int): String? = serials.getOrNull(index)?.takeIf { it.isNotBlank() }

    /** Requête AQL des objets en stock correspondant à un nom (les plus anciens d'abord). */
    fun aqlInStock(typeId: String, name: String): String =
        "objectSchemaId = ${StockItConfig.jiraAssetsSchemaId} AND objectTypeId = $typeId " +
            "AND \"Asset Status\" = \"$STATUS_IN\" AND Name like \"${aqlEscape(name)}\" ORDER BY Created ASC"

    fun aqlEscape(s: String): String = s.replace("\\", " ").replace("\"", " ").replace(Regex("""\s+"""), " ").trim()

    private fun normalize(s: String): String {
        val map = mapOf('à' to 'a', 'â' to 'a', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e', 'î' to 'i',
            'ï' to 'i', 'ô' to 'o', 'ö' to 'o', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ç' to 'c')
        return s.lowercase().map { map[it] ?: it }.joinToString("")
            .replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    // ------------------------------------------------------------------ HTTP

    private fun basicAuth(): String =
        "Basic " + Base64.encode("${StockItConfig.jiraUserEmail}:${StockItConfig.jiraApiToken}".encodeToByteArray())

    private suspend fun workspace(): String? {
        workspaceId?.let { return it }
        val resp = Http.client.get("${StockItConfig.jiraBaseUrl.trimEnd('/')}/rest/servicedeskapi/assets/workspace") {
            header("Authorization", basicAuth()); header("Accept", "application/json")
        }
        if (!resp.status.isSuccess()) return null
        val id = runCatching {
            Http.json.parseToJsonElement(resp.bodyAsText()).jsonObject["values"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.str("workspaceId")
        }.getOrNull()
        workspaceId = id
        return id
    }

    private suspend fun api(): String? = workspace()?.let { "https://api.atlassian.com/jsm/assets/workspace/$it/v1" }

    /** Nom d'attribut → ID, pour un type d'objet (mis en cache). */
    private suspend fun attributes(typeId: String): Map<String, String> {
        attributeCache[typeId]?.let { return it }
        val base = api() ?: return emptyMap()
        val resp = Http.client.get("$base/objecttype/$typeId/attributes") {
            header("Authorization", basicAuth()); header("Accept", "application/json")
        }
        if (!resp.status.isSuccess()) return emptyMap()
        val map = runCatching {
            (Http.json.parseToJsonElement(resp.bodyAsText()) as JsonArray).mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                val n = o.str("name") ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                n.lowercase() to id
            }.toMap()
        }.getOrDefault(emptyMap())
        if (map.isNotEmpty()) attributeCache[typeId] = map
        return map
    }

    private fun body(typeId: String, attrs: Map<String, String>, values: Map<String, String?>): String = buildJsonObject {
        put("objectTypeId", typeId)
        putJsonArray("attributes") {
            values.forEach { (name, value) ->
                val id = attrs[name.lowercase()] ?: return@forEach
                if (value == null) return@forEach
                addJsonObject {
                    put("objectTypeAttributeId", id)
                    putJsonArray("objectAttributeValues") { addJsonObject { put("value", value) } }
                }
            }
        }
    }.toString()

    // ------------------------------------------------------------------ lecture (synchronisation)

    data class FetchResult(val units: List<com.example.stockit.shared.ai.AssetGrouping.AssetUnit>?, val error: String?)

    /**
     * Lit TOUS les objets des types configurés (périphériques, ordinateurs, écrans) du schéma,
     * pagination comprise. Lecture seule. units=null + error si Jira est injoignable.
     */
    @Throws(Throwable::class)
    suspend fun fetchAll(): FetchResult {
        if (!isConfigured) return FetchResult(null, "assets_not_configured")
        return runCatching {
            val base = api() ?: return@runCatching FetchResult(null, "assets_workspace_unavailable")
            val types = listOf(
                StockItConfig.jiraAssetsTypePeripherals, StockItConfig.jiraAssetsTypeComputers, StockItConfig.jiraAssetsTypeMonitors,
            ).filter { it.isNotBlank() }.distinct()
            val out = mutableListOf<com.example.stockit.shared.ai.AssetGrouping.AssetUnit>()
            for (typeId in types) {
                val byId = attributes(typeId).entries.associate { (name, id) -> id to name } // id → nom (minuscules)
                var start = 0
                while (true) {
                    val resp = Http.client.post("$base/object/aql") {
                        parameter("startAt", start); parameter("maxResults", 100); parameter("includeAttributes", true)
                        header("Authorization", basicAuth()); header("Accept", "application/json")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("qlQuery", "objectSchemaId = ${StockItConfig.jiraAssetsSchemaId} AND objectTypeId = $typeId")
                        }.toString())
                    }
                    val text = resp.bodyAsText()
                    if (!resp.status.isSuccess()) {
                        return@runCatching FetchResult(null, "AQL HTTP ${resp.status.value} ${text.trimForLog(150)}")
                    }
                    val root = Http.json.parseToJsonElement(text).jsonObject
                    val values = root["values"]?.jsonArray.orEmpty()
                    for (v in values) {
                        val o = v as? JsonObject ?: continue
                        val attrs = mutableMapOf<String, String>()
                        (o["attributes"] as? JsonArray)?.forEach { a ->
                            val ao = a as? JsonObject ?: return@forEach
                            val name = byId[ao.str("objectTypeAttributeId") ?: return@forEach] ?: return@forEach
                            val value = (ao["objectAttributeValues"] as? JsonArray)?.mapNotNull { x ->
                                (x as? JsonObject)?.let { it.str("displayValue") ?: it.str("value") }
                            }?.joinToString(", ").orEmpty()
                            if (value.isNotBlank()) attrs[name] = value
                        }
                        out += com.example.stockit.shared.ai.AssetGrouping.AssetUnit(
                            objectKey = o.str("objectKey") ?: o.str("id") ?: continue,
                            typeId = typeId,
                            label = o.str("label") ?: attrs["name"].orEmpty(),
                            status = attrs["asset status"].orEmpty(),
                            brand = attrs["brand"] ?: attrs["device name"],
                            model = attrs["asset model"],
                            serial = attrs["serial number"],
                            poNumber = attrs["po number"],
                            vendor = attrs["vendor name"],
                            assetType = attrs["asset type"],
                        )
                    }
                    val total = root.int("total", values.size)
                    start += values.size
                    if (values.isEmpty() || start >= total || start >= 5000) break
                }
            }
            FetchResult(out, null)
        }.getOrElse { FetchResult(null, "${it::class.simpleName}: ${it.message}") }
    }

    /**
     * Renomme des unités existantes (nom modifié dans l'app). [units] : (clé, numéro de série).
     * Le nom suit la convention du schéma : « <nom> - SN <série> » ou « <nom> #001 ».
     */
    @Throws(Throwable::class)
    suspend fun rename(units: List<Pair<String, String?>>, typeId: String, newName: String, brand: String?): OpResult {
        if (!isConfigured) return OpResult(false, "assets_not_configured")
        if (units.isEmpty() || newName.isBlank()) return OpResult(false, "nothing_to_update")
        if (StockItConfig.testMode) return OpResult(true, tr("TEST — renommage simulé", "TEST — rename simulated"))
        return runCatching {
            val base = api() ?: return@runCatching OpResult(false, "assets_workspace_unavailable")
            val attrs = attributes(typeId)
            if (attrs.isEmpty()) return@runCatching OpResult(false, "assets_attributes_unavailable (type $typeId)")
            // Clé SIT → id interne (le PUT attend l'id)
            val keys = units.map { it.first }
            val search = Http.client.post("$base/object/aql") {
                parameter("maxResults", keys.size); parameter("includeAttributes", false)
                header("Authorization", basicAuth()); header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("qlQuery", "objectSchemaId = ${StockItConfig.jiraAssetsSchemaId} AND Key IN (" +
                        keys.joinToString(",") { "\"${aqlEscape(it)}\"" } + ")")
                }.toString())
            }
            val ids = runCatching {
                Http.json.parseToJsonElement(search.bodyAsText()).jsonObject["values"]?.jsonArray?.mapNotNull {
                    val o = it as? JsonObject ?: return@mapNotNull null
                    (o.str("objectKey") ?: return@mapNotNull null) to (o.str("id") ?: return@mapNotNull null)
                }?.toMap()
            }.getOrNull().orEmpty()
            var done = 0
            units.forEachIndexed { i, (key, serial) ->
                val id = ids[key] ?: return@forEachIndexed
                val resp = Http.client.put("$base/object/$id") {
                    header("Authorization", basicAuth()); header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(body(typeId, attrs, mapOf(
                        "Name" to objectLabel(newName, i, units.size, serial),
                        "Brand" to brand?.takeIf { it.isNotBlank() },
                    )))
                }
                if (resp.status.isSuccess()) done++
            }
            if (done == 0) OpResult(false, "assets_update_failed") else OpResult(true, "$done/${units.size}")
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }

    // ------------------------------------------------------------------ opérations

    /**
     * Entrée en stock : crée [qty] objets « In stock » (max [MAX_UNITS]).
     * success=true → detail = clés créées (« SIT-123, SIT-124 »).
     */
    @Throws(Throwable::class)
    suspend fun checkIn(
        name: String, qty: Int, brand: String?, model: String?, poNumber: String?, vendor: String?, serials: List<String>,
    ): OpResult {
        if (!isConfigured) return OpResult(false, "assets_not_configured")
        if (qty <= 0 || name.isBlank()) return OpResult(false, "nothing_to_create")
        val units = minOf(qty, MAX_UNITS)
        val kind = kindFor(name)
        if (StockItConfig.testMode) return OpResult(true, tr("TEST — $units objet(s) ${kind.name.lowercase()} simulé(s)", "TEST — $units ${kind.name.lowercase()} object(s) simulated"))
        return runCatching {
            val base = api() ?: return@runCatching OpResult(false, "assets_workspace_unavailable")
            val typeId = typeIdFor(kind)
            val attrs = attributes(typeId)
            if (attrs.isEmpty()) return@runCatching OpResult(false, "assets_attributes_unavailable (type $typeId)")
            val keys = mutableListOf<String>()
            var lastError: String? = null
            for (i in 0 until units) {
                val serial = serialFor(serials, i)
                val values = mapOf(
                    "Name" to objectLabel(name, i, units, serial),
                    "Device Name" to brand,
                    "Brand" to brand,
                    "Asset Model" to model,
                    "Asset Status" to STATUS_IN,
                    "Asset Sub-Status" to SUB_STATUS_IN,
                    "Asset Type" to assetTypeLabel(kind, name),
                    "Geo Location" to StockItConfig.jiraAssetsLocation,
                    "PO Number" to poNumber?.takeIf { it.isNotBlank() },
                    "Vendor Name" to vendor?.takeIf { it.isNotBlank() },
                    "Serial Number" to serial,
                )
                val resp = Http.client.post("$base/object/create") {
                    header("Authorization", basicAuth()); header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(body(typeId, attrs, values))
                }
                val text = resp.bodyAsText()
                if (resp.status.isSuccess()) {
                    runCatching { Http.json.parseToJsonElement(text).jsonObject.str("objectKey") }.getOrNull()?.let { keys += it }
                } else {
                    lastError = "HTTP ${resp.status.value} ${text.trimForLog(150)}"
                    break
                }
            }
            when {
                keys.isEmpty() -> OpResult(false, lastError ?: "assets_create_failed")
                lastError != null -> OpResult(true, keys.joinToString(", ") + tr(" (arrêt : $lastError)", " (stopped: $lastError)"))
                else -> OpResult(true, keys.joinToString(", "))
            }
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }

    /**
     * Sortie de stock : passe [qty] objets « In stock » correspondant à [name] en « OUT / Checked Out ».
     * success=true → detail = clés mises à jour. success=false + « no_asset_found » si rien ne correspond.
     */
    @Throws(Throwable::class)
    suspend fun checkOut(
        name: String, qty: Int, assignedUser: String?, ticketKey: String?,
        preferredKeys: List<String> = emptyList(), preferredTypeId: String? = null,
        status: String = STATUS_OUT, subStatus: String = SUB_STATUS_OUT,
    ): OpResult {
        if (!isConfigured) return OpResult(false, "assets_not_configured")
        if (qty <= 0 || name.isBlank()) return OpResult(false, "nothing_to_update")
        val units = minOf(qty, MAX_UNITS)
        if (StockItConfig.testMode) return OpResult(true, tr("TEST — $units objet(s) passé(s) à OUT (simulé)", "TEST — $units object(s) set to OUT (simulated)"))
        return runCatching {
            val base = api() ?: return@runCatching OpResult(false, "assets_workspace_unavailable")
            val typeId = preferredTypeId?.takeIf { preferredKeys.isNotEmpty() } ?: typeIdFor(kindFor(name))
            val attrs = attributes(typeId)
            if (attrs.isEmpty()) return@runCatching OpResult(false, "assets_attributes_unavailable (type $typeId)")
            // Unités connues grâce à la synchronisation : on cible leurs clés exactes (toujours « In stock »).
            val aql = if (preferredKeys.isNotEmpty()) {
                "objectSchemaId = ${StockItConfig.jiraAssetsSchemaId} AND \"Asset Status\" = \"$STATUS_IN\" AND Key IN (" +
                    preferredKeys.take(200).joinToString(",") { "\"${aqlEscape(it)}\"" } + ") ORDER BY Created ASC"
            } else aqlInStock(typeId, name)
            val search = Http.client.post("$base/object/aql") {
                parameter("maxResults", units); parameter("includeAttributes", false)
                header("Authorization", basicAuth()); header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("qlQuery", aql) }.toString())
            }
            val text = search.bodyAsText()
            if (!search.status.isSuccess()) return@runCatching OpResult(false, "AQL HTTP ${search.status.value} ${text.trimForLog(150)}")
            val found = runCatching {
                Http.json.parseToJsonElement(text).jsonObject["values"]?.jsonArray?.mapNotNull {
                    val o = it as? JsonObject ?: return@mapNotNull null
                    val id = o.str("id") ?: return@mapNotNull null
                    id to (o.str("objectKey") ?: id)
                }
            }.getOrNull().orEmpty().take(units)
            if (found.isEmpty()) return@runCatching OpResult(false, "no_asset_found")
            val note = listOfNotNull(
                assignedUser?.takeIf { it.isNotBlank() },
                ticketKey?.takeIf { it.isNotBlank() && !it.equals("NO-TICKET", true) }?.let { "($it)" },
            ).joinToString(" ").ifBlank { null }
            val keys = mutableListOf<String>()
            for ((id, key) in found) {
                val resp = Http.client.put("$base/object/$id") {
                    header("Authorization", basicAuth()); header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(body(typeId, attrs, mapOf(
                        "Asset Status" to status,
                        "Asset Sub-Status" to subStatus,
                        "Assigned User" to note?.takeIf { it.isNotBlank() },
                    )))
                }
                if (resp.status.isSuccess()) keys += key
            }
            if (keys.isEmpty()) OpResult(false, "assets_update_failed") else {
                val partial = if (keys.size < units) tr(" (${keys.size}/$units trouvés dans Assets)", " (${keys.size}/$units found in Assets)") else ""
                OpResult(true, keys.joinToString(", ") + partial)
            }
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }
}
