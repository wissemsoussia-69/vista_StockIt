package com.example.stockit.shared

import app.cash.sqldelight.db.SqlDriver
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.db.StockItDatabase
import com.example.stockit.shared.network.Http
import com.example.stockit.shared.network.StockItApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** In-memory database with the CURRENT schema. */
expect fun createTestDriver(): SqlDriver

/** In-memory database WITHOUT any schema (used to replay an old schema and test migrations). */
expect fun createEmptyTestDriver(): SqlDriver

fun newTestRepository(): Pair<StockItRepository, SqlDriver> {
    val driver = createTestDriver()
    return StockItRepository(StockItDatabase(driver), "test-device") to driver
}

/** Captured outgoing HTTP request (body decoded as text). */
data class Captured(val method: String, val url: String, val headers: Map<String, String>, val body: String)

/**
 * Installs a MockEngine for every HTTP call made by the shared module.
 * [handler] returns (status, body) for a request; every request is recorded in [requests].
 */
class FakeHttp(private val handler: (Captured) -> Pair<Int, String> = { 200 to "{}" }) {
    val requests = mutableListOf<Captured>()

    fun install() {
        Http.testEngine = MockEngine { req -> respondTo(req) }
    }

    private fun MockRequestHandleScope.respondTo(req: HttpRequestData): HttpResponseData {
        val body = when (val c = req.body) {
            is OutgoingContent.ByteArrayContent -> c.bytes().decodeToString()
            else -> ""
        }
        val cap = Captured(
            req.method.value, req.url.toString(),
            req.headers.entries().associate { it.key to it.value.joinToString(",") }, body,
        )
        requests += cap
        val (status, text) = handler(cap)
        return respond(text, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

/** Resets every global the tests touch, so tests stay independent of execution order. */
fun resetGlobals() {
    Http.testEngine = null
    StockItApi.accessTokenProvider = null
    StockItConfig.testMode = false
    StockItConfig.apiBaseUrl = ""
    StockItConfig.jiraBaseUrl = ""
    StockItConfig.jiraUserEmail = ""
    StockItConfig.jiraApiToken = ""
    StockItConfig.jiraProjectKey = ""
    StockItConfig.jiraAssetsSchemaId = ""
    StockItConfig.jiraAssetsTypePeripherals = ""
    StockItConfig.jiraAssetsTypeComputers = ""
    StockItConfig.jiraAssetsTypeMonitors = ""
    StockItConfig.cimpressGatewayKey = ""
    StockItConfig.claudeApiKey = ""
    StockItConfig.slackWebhookUrl = ""
    StockItConfig.slackBotToken = ""
    StockItConfig.stockitSlackWebhookUrl = ""
    StockItConfig.stockitWebhookUrl = ""
    StockItConfig.stockitWebhookSecret = ""
    StockItConfig.stockitEventWebhookUrl = ""
    StockItConfig.stockitAssetStatusWebhookUrl = ""
    StockItConfig.ssoDefaultRole = "USER"
    StockItConfig.localLoginEnabled = false
}

/** Waits (real time) until [cond] is true — for work launched on the repository's background scope. */
suspend fun eventually(timeoutMs: Long = 3_000, cond: suspend () -> Boolean): Boolean = withContext(Dispatchers.Default) {
    var waited = 0L
    while (waited < timeoutMs) {
        if (cond()) return@withContext true
        delay(25); waited += 25
    }
    cond()
}
