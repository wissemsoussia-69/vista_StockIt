package com.example.stockit.shared

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.example.stockit.shared.ai.DeliveryNoteParser
import com.example.stockit.shared.ai.PackageLabelParser
import com.example.stockit.shared.ai.TicketMatcher
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.JiraTicket
import com.example.stockit.shared.network.JiraClient
import com.example.stockit.shared.network.SlackNotifier
import com.example.stockit.shared.network.StockItApi
import com.example.stockit.shared.security.WebhookSigner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Threat model T-08, T-10, T-12, T-13, T-15, T-17 — outbound integrations. */
class IntegrationSecurityTest {

    @BeforeTest fun setUp() = resetGlobals()
    @AfterTest fun tearDown() = resetGlobals()

    private fun SqlDriver.outboxBodies(): List<String> = executeQuery(null, "SELECT body FROM outbox", { c ->
        val l = mutableListOf<String>(); while (c.next().value) l += c.getString(0)!!; QueryResult.Value(l)
    }, 0).value

    private fun cimpress(answer: String) = FakeHttp { 200 to """{"choices":[{"message":{"content":${Json.encodeToString(String.serializer(), answer)}}}]}""" }
        .also { StockItConfig.cimpressGatewayKey = "test-key"; it.install() }

    private fun jira() {
        StockItConfig.jiraBaseUrl = "https://jira.test"
        StockItConfig.jiraUserEmail = "svc@test"
        StockItConfig.jiraApiToken = "token"
        StockItConfig.jiraProjectKey = "SD"
    }

    // ------------------------------------------------------------------ T-08 n8n webhooks

    @Test
    fun webhookSecretIsNeverPersistedAndRequestsAreSigned() = runTest {
        val (repo, driver) = newTestRepository()
        StockItConfig.stockitEventWebhookUrl = "https://n8n.test/webhook/stockit/event"
        StockItConfig.stockitWebhookSecret = "s3cr3t-value"
        val http = FakeHttp { 503 to """{"ok":false}""" }.also { it.install() }

        repo.loginSso("auth0|boss", "boss", "ADMIN")
        repo.addProduct("Casque EPOS", "Périphérique", null, null, "T1", 2, 0.0, "", "", null)
        assertTrue(eventually { driver.outboxBodies().isNotEmpty() }, "failed delivery is queued")

        val stored = driver.outboxBodies().single()
        assertFalse(stored.contains("s3cr3t-value"), "outbox must not store the shared secret: $stored")
        assertNotNull(Json.parseToJsonElement(stored).jsonObject["event_id"], "idempotency key for safe replays")

        val sent = http.requests.single { it.url.startsWith(StockItConfig.stockitEventWebhookUrl) }
        val ts = sent.headers["X-StockIT-Timestamp"]
        assertNotNull(ts, "timestamp header")
        assertEquals(WebhookSigner.sign("s3cr3t-value", ts, sent.body), sent.headers["X-StockIT-Signature"])
    }

    @Test
    fun failedWebhooksBackOffInsteadOfHammeringTheEndpoint() = runTest {
        val (repo, driver) = newTestRepository()
        StockItConfig.stockitEventWebhookUrl = "https://n8n.test/webhook/stockit/event"
        val http = FakeHttp { 503 to "" }.also { it.install() }
        repo.loginSso("auth0|boss", "boss", "ADMIN")
        repo.addProduct("Casque EPOS", "Périphérique", null, null, "T1", 2, 0.0, "", "", null)
        assertTrue(eventually { driver.outboxBodies().isNotEmpty() })

        val before = http.requests.size
        repo.flushOutbox()
        assertEquals(before, http.requests.size, "an item that just failed is not retried immediately")
        assertEquals(1L, repo.pendingWebhookCount())
    }

    @Test
    fun webhookSignatureIsHmacOfTimestampAndBody() {
        val sig = WebhookSigner.sign("key", "1700000000", """{"a":1}""")
        assertTrue(sig.startsWith("sha256="))
        assertEquals(64, sig.removePrefix("sha256=").length)
        assertFalse(sig == WebhookSigner.sign("key", "1700000001", """{"a":1}"""), "timestamp is covered")
        assertFalse(sig == WebhookSigner.sign("key", "1700000000", """{"a":2}"""), "body is covered")
        assertFalse(sig == WebhookSigner.sign("other", "1700000000", """{"a":1}"""), "secret is covered")
    }

    // ------------------------------------------------------------------ T-12 Jira keys

    @Test
    fun jiraIssueKeysAreValidatedBeforeAnyRequest() = runTest {
        jira()
        val http = FakeHttp { 200 to """{"key":"SD-12","fields":{"summary":"x"}}""" }.also { it.install() }
        for (bad in listOf("../../rest/api/3/myself", "SD-1?expand=all", "SD-1/comment", "SD 1", "1-SD", "")) {
            val r = JiraClient.getIssue(bad)
            assertEquals(null, r.ticket, bad)
            assertFalse(JiraClient.addComment(bad, "hello").success, bad)
        }
        assertEquals(0, http.requests.size, "no request may leave with a malformed key: ${http.requests.map { it.url }}")

        assertEquals("SD-12", JiraClient.getIssue("sd-12").ticket?.key)
        assertEquals("https://jira.test/rest/api/3/issue/SD-12?fields=summary%2Cstatus%2Cpriority%2Cduedate%2Cdescription%2Cassignee%2Creporter%2Clabels",
            http.requests.single().url)
    }

    // ------------------------------------------------------------------ T-13 claims backend

    @Test
    fun claimsBackendFailsClosedWithoutToken() = runTest {
        val (repo, _) = newTestRepository()
        StockItConfig.apiBaseUrl = "https://api.test/"
        val http = FakeHttp { 200 to "[]" }.also { it.install() }
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        repo.getClaims(syncRemote = true)
        repo.addClaim("PC lent", null, "HIGH")
        eventually(500) { http.requests.isNotEmpty() } // addClaim posts in the background
        assertEquals(0, http.requests.size, "no anonymous call to the backend")

        StockItApi.accessTokenProvider = { "tok-1" }
        repo.getClaims(syncRemote = true)
        assertEquals("Bearer tok-1", http.requests.single().headers["Authorization"])
    }

    @Test
    fun claimsBackendRefusesCleartextOutsideLocalhost() = runTest {
        val (repo, _) = newTestRepository()
        val http = FakeHttp { 200 to "[]" }.also { it.install() }
        StockItApi.accessTokenProvider = { "tok-1" }
        repo.loginSso("auth0|tech", "tech", "TECHNICIEN")
        StockItConfig.apiBaseUrl = "http://api.example.com/"
        repo.getClaims(syncRemote = true)
        assertEquals(0, http.requests.size, "bearer token must never travel over plain HTTP")
        StockItConfig.apiBaseUrl = "http://localhost/api/"
        repo.getClaims(syncRemote = true)
        assertEquals(1, http.requests.size, "simulator backend on localhost stays usable")
    }

    // ------------------------------------------------------------------ T-15 Slack

    @Test
    fun slackMessagesCannotPingTheWholeChannel() = runTest {
        StockItConfig.slackWebhookUrl = "https://hooks.slack.test/T/B/X"
        val http = FakeHttp { 200 to "ok" }.also { it.install() }
        SlackNotifier.send("🚨 Produit <!channel> & <https://evil.example|cliquez>")
        val text = Json.parseToJsonElement(http.requests.single().body).jsonObject["text"]!!.jsonPrimitive.content
        assertFalse(text.contains("<!channel>"), text)
        assertFalse(text.contains("<https://evil.example"), text)
        assertTrue(text.contains("&lt;!channel&gt; &amp; "), text)
    }

    @Test
    fun slackWebhookMustUseHttps() = runTest {
        StockItConfig.slackWebhookUrl = "http://hooks.slack.test/T/B/X"
        val http = FakeHttp { 200 to "ok" }.also { it.install() }
        assertFalse(SlackNotifier.send("hello").success)
        assertEquals(0, http.requests.size)
    }

    // ------------------------------------------------------------------ T-10 AI output

    @Test
    fun aiAssignmentsAreLimitedToTheTicketsThatWereSent() = runTest {
        cimpress("""[{"ticketId":"SD-1","qty":1,"reason":"ok"},{"ticketId":"EVIL-666","qty":3,"reason":"injected"}]""")
        val r = TicketMatcher.assign("Souris", 4, listOf(JiraTicket("SD-1", summary = "Besoin d'une souris")))
        assertEquals(listOf("SD-1"), r.assignments!!.map { it.ticketId })
    }

    @Test
    fun parseAssignmentsDropsUnknownTicketIds() {
        val r = TicketMatcher.parseAssignments("""[{"ticketId":"SD-9","qty":2},{"ticketId":"SD-1","qty":2}]""", 4, setOf("SD-1"))
        assertEquals(listOf("SD-1"), r.map { it.ticketId })
    }

    @Test
    fun aiLabelQuantityIsBounded() = runTest {
        cimpress("""{"productName":"IMPACT 100","quantity":2147483647,"brand":"EPOS"}""")
        val l = PackageLabelParser.parseOcr("EPOS IMPACT 100 QTY 20")
        assertTrue(l.quantity in 1..PackageLabelParser.MAX_QUANTITY, "quantity=${l.quantity}")
    }

    @Test
    fun ocrTextSentToTheModelIsCapped() = runTest {
        val http = cimpress("""{"supplier":"Dell","purchaseOrders":[]}""")
        DeliveryNoteParser.parseOcr("PO 1234 " + "x".repeat(200_000))
        assertTrue(http.requests.single().body.length < 20_000, "size=${http.requests.single().body.length}")
    }

    // ------------------------------------------------------------------ T-17 error disclosure

    @Test
    fun parserErrorsDoNotEchoModelOutput() = runTest {
        cimpress("this is not json INTERNAL-PROMPT-LEAK")
        val n = DeliveryNoteParser.parseOcr("Facture PO 1234")
        assertNotNull(n.error)
        assertFalse(n.error!!.contains("INTERNAL-PROMPT-LEAK"), n.error!!)
    }

    @Test
    fun jiraErrorsKeepTheMessageButNotTheRawBody() = runTest {
        jira()
        FakeHttp { 400 to """{"errorMessages":["Field 'summary' is required"],"debug":"java.lang.IllegalStateException at com.atlassian"}""" }.install()
        val r = JiraClient.createTask("SD", "x", "y")
        assertFalse(r.success)
        assertTrue(r.detail!!.contains("Field 'summary' is required"), r.detail!!)
        assertFalse(r.detail!!.contains("java.lang"), r.detail!!)
    }
}
