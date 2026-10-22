package com.example.stockit.shared.network

import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.JiraTicket
import com.example.stockit.shared.model.OpResult
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class JiraSearchResult(val tickets: List<JiraTicket>?, val error: String?)
data class JiraIssueResult(val ticket: JiraTicket?, val error: String?)

/**
 * Jira Cloud REST v3 — portage de util/JiraClient.java (création) et util/JiraReader.java (lecture).
 * Auth : Basic (email + API token).
 */
object JiraClient {

    private const val FIELDS = "summary,status,priority,duedate,description,assignee,reporter,labels"

    private fun basicAuth(): String =
        "Basic " + Base64.encode("${StockItConfig.jiraUserEmail}:${StockItConfig.jiraApiToken}".encodeToByteArray())

    private fun base(): String = StockItConfig.jiraBaseUrl.trimEnd('/')

    /** Crée une tâche. success=true → detail = clé du ticket (ex "SD-1234"). */
    @Throws(Throwable::class)
    suspend fun createTask(projectKey: String, summary: String, details: String?): OpResult {
        if (!StockItConfig.isJiraConfigured) return OpResult(false, "jira_credentials_missing")
        if (StockItConfig.testMode) return OpResult(true, "TEST-" + (1000..9999).random())
        return runCatching {
            val issueType = StockItConfig.jiraIssueType.ifBlank { "Task" }
            val body = buildJsonObject {
                putJsonObject("fields") {
                    putJsonObject("project") { put("key", projectKey) }
                    put("summary", summary)
                    putJsonObject("issuetype") { put("name", issueType) }
                    putJsonArray("labels") { add("StockIT-PFE"); add("AutoCreated") }
                    if (StockItConfig.jiraComponent.isNotBlank()) {
                        putJsonArray("components") { addJsonObject { put("name", StockItConfig.jiraComponent) } }
                    }
                    // Le projet SD exige le Cost Center ID (customfield_11485)
                    if (StockItConfig.jiraCostCenter.isNotBlank()) {
                        put("customfield_11485", StockItConfig.jiraCostCenter)
                    }
                    putJsonObject("description") {
                        put("type", "doc")
                        put("version", 1)
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "paragraph")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", details ?: "")
                                    }
                                }
                            }
                        }
                    }
                }
            }.toString()
            val resp = Http.client.post("${base()}/rest/api/3/issue") {
                header("Authorization", basicAuth())
                header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val payload = resp.bodyAsText()
            if (resp.status.isSuccess()) {
                val key = runCatching { Http.json.parseToJsonElement(payload).jsonObject.str("key") }.getOrNull()
                OpResult(true, key ?: "OK")
            } else {
                OpResult(false, "HTTP ${resp.status.value} ${payload.trimForLog()}")
            }
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }

    /** Tickets ouverts du projet configuré, triés priorité DESC puis échéance ASC. */
    @Throws(Throwable::class)
    suspend fun loadOpenTickets(maxResults: Int): JiraSearchResult {
        val jql = "project = ${StockItConfig.jiraProjectKey} AND statusCategory != Done ORDER BY priority DESC, duedate ASC"
        return searchByJql(jql, maxResults)
    }

    @Throws(Throwable::class)
    suspend fun searchByJql(jql: String, maxResults: Int): JiraSearchResult {
        if (!StockItConfig.isJiraConfigured) return JiraSearchResult(null, "jira_credentials_missing")
        return runCatching {
            val resp = Http.client.get("${base()}/rest/api/3/search/jql") {
                parameter("jql", jql)
                parameter("fields", FIELDS)
                parameter("maxResults", maxOf(1, maxResults))
                header("Authorization", basicAuth())
                header("Accept", "application/json")
            }
            val payload = resp.bodyAsText()
            if (!resp.status.isSuccess()) {
                return@runCatching JiraSearchResult(null, "HTTP ${resp.status.value} ${payload.trimForLog(150)}")
            }
            val issues = Http.json.parseToJsonElement(payload).jsonObject["issues"] as? JsonArray
            JiraSearchResult(issues?.mapNotNull { (it as? JsonObject)?.let(::parseIssue) } ?: emptyList(), null)
        }.getOrElse { JiraSearchResult(null, "${it::class.simpleName}: ${it.message}") }
    }

    /** Récupère un ticket par sa clé (validation d'une saisie manuelle). */
    @Throws(Throwable::class)
    suspend fun getIssue(key: String): JiraIssueResult {
        if (!StockItConfig.isJiraConfigured) return JiraIssueResult(null, "jira_credentials_missing")
        return runCatching {
            val resp = Http.client.get("${base()}/rest/api/3/issue/${key.trim()}") {
                parameter("fields", FIELDS)
                header("Authorization", basicAuth())
                header("Accept", "application/json")
            }
            val payload = resp.bodyAsText()
            if (!resp.status.isSuccess()) return@runCatching JiraIssueResult(null, "HTTP ${resp.status.value}")
            JiraIssueResult(parseIssue(Http.json.parseToJsonElement(payload).jsonObject), null)
        }.getOrElse { JiraIssueResult(null, "${it::class.simpleName}: ${it.message}") }
    }

    /**
     * Ajoute un commentaire (texte multi-lignes → paragraphes ADF) sur un ticket.
     * Utilisé après une sortie de stock pour tracer la livraison dans le ticket SD.
     */
    @Throws(Throwable::class)
    suspend fun addComment(key: String, text: String): OpResult {
        if (!StockItConfig.isJiraConfigured) return OpResult(false, "jira_credentials_missing")
        if (key.isBlank() || key.equals("NO-TICKET", ignoreCase = true)) return OpResult(false, "no_ticket")
        if (StockItConfig.testMode) return OpResult(true, "test_mode")
        return runCatching {
            val body = buildJsonObject {
                putJsonObject("body") {
                    put("type", "doc")
                    put("version", 1)
                    putJsonArray("content") {
                        text.split('\n').filter { it.isNotBlank() }.forEach { line ->
                            addJsonObject {
                                put("type", "paragraph")
                                putJsonArray("content") { addJsonObject { put("type", "text"); put("text", line) } }
                            }
                        }
                    }
                }
            }.toString()
            val resp = Http.client.post("${base()}/rest/api/3/issue/${key.trim()}/comment") {
                header("Authorization", basicAuth())
                header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (resp.status.isSuccess()) OpResult(true, key)
            else OpResult(false, "HTTP ${resp.status.value} ${resp.bodyAsText().trimForLog(150)}")
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }

    fun browseUrl(key: String): String = "${base()}/browse/$key"

    // ---------------- Parsing ----------------

    internal fun parseIssue(o: JsonObject): JiraTicket {
        val f = o.obj("fields")
        val descRaw = f?.get("description")
        val description = when (descRaw) {
            is JsonObject -> flattenAdf(descRaw)
            is JsonPrimitive -> descRaw.contentOrNull
            else -> null
        }
        return JiraTicket(
            key = o.str("key") ?: "",
            summary = f?.str("summary"),
            status = f?.obj("status")?.str("name"),
            priority = f?.obj("priority")?.str("name"),
            dueDate = f?.str("duedate"),
            description = description,
            assignee = f?.obj("assignee")?.str("displayName"),
            reporter = f?.obj("reporter")?.str("displayName"),
        )
    }

    /** Aplatit un document ADF (Atlassian Document Format) en texte. */
    fun flattenAdf(node: JsonObject): String = buildString { flattenInto(node, this) }.trim()

    private fun flattenInto(node: JsonElement?, sb: StringBuilder) {
        when (node) {
            is JsonObject -> {
                val type = (node["type"] as? JsonPrimitive)?.contentOrNull ?: ""
                if (type == "text") {
                    sb.append((node["text"] as? JsonPrimitive)?.contentOrNull ?: "").append(' ')
                    return
                }
                (node["content"] as? JsonArray)?.forEach { flattenInto(it, sb) }
                if (type == "paragraph" || type == "heading" || type == "listItem") sb.append('\n')
            }
            is JsonArray -> node.forEach { flattenInto(it, sb) }
            else -> Unit
        }
    }
}
