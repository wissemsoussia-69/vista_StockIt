package com.example.stockit.shared.network

import com.example.stockit.shared.StockIt
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.OpResult
import com.example.stockit.shared.platform.nowMs
import com.example.stockit.shared.security.WebhookSigner
import com.example.stockit.shared.security.randomId
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Webhook interne StockIT (n8n / n8n.example.com) — portage de util/StockItReporter.java.
 * Le workflow n8n compose et envoie l'e-mail ; `barcode` porte le suffixe du sujet.
 */
object StockItReporter {

    /**
     * Événement avec destinataire explicite. L'utilisateur est lu depuis la session
     * ("stockit-system" si personne n'est connecté).
     */
    @Throws(Throwable::class)
    suspend fun sendEvent(subjectSuffix: String, message: String, recipientEmail: String): OpResult {
        val user = StockIt.sessionOrNull?.username?.takeIf { it.isNotBlank() } ?: "stockit-system"
        return sendReport(user, recipientEmail, subjectSuffix, message)
    }

    /** Événement vers le destinataire par défaut (e-mail de la session, sinon défaut projet). */
    @Throws(Throwable::class)
    suspend fun sendEventToSession(event: String, message: String): OpResult {
        val s = StockIt.sessionOrNull
        val user = s?.username?.takeIf { it.isNotBlank() } ?: "stockit-system"
        val email = s?.email?.takeIf { it.isNotBlank() } ?: StockItConfig.defaultRecipientEmail
        return sendReport(user, email, event, message)
    }

    /**
     * Mouvement de stock → workflow n8n `stockit/event`.
     * Champs requis par le workflow : barcode, product, qty (> 0), timestamp, user_email.
     */
    @Throws(Throwable::class)
    suspend fun sendStockEvent(
        barcode: String, product: String, qty: Int, type: String, reason: String?, ticketId: String?, timestampIso: String,
    ): OpResult {
        val url = StockItConfig.stockitEventWebhookUrl
        if (StockItConfig.testMode) return OpResult(true, "test_mode")
        if (url.isBlank() || qty <= 0) return OpResult(false, "event_webhook_not_configured")
        val s = StockIt.sessionOrNull
        return runCatching {
            val body = buildJsonObject {
                put("barcode", barcode)
                put("product", product)
                put("qty", qty)
                put("type", type)
                put("reason", reason ?: "")
                put("ticket_id", ticketId ?: "")
                put("timestamp", timestampIso)
                put("user", s?.username ?: "stockit-system")
                put("user_email", s?.email?.takeIf { it.isNotBlank() } ?: StockItConfig.defaultRecipientEmail)
                put("source", "ios")
                put("event_id", randomId())
            }.toString()
            val resp = signedPost(url, body)
            OpResult(resp.status.isSuccess(), "HTTP ${resp.status.value}")
        }.getOrElse { OpResult(false, it.message) }
    }

    /**
     * Statut d'un actif → workflow n8n `stockit/asset-status`.
     * Payload : { source, at, assetTag, status: IN_STOCK|OUT, note, context{…} }
     */
    internal fun assetStatusBody(assetTag: String, status: String, note: String?, product: String?, qty: Int,
                                 ticketId: String?, atIso: String): String {
        val s = StockIt.sessionOrNull
        return buildJsonObject {
            put("source", "stockit-ios")
            put("at", atIso)
            put("assetTag", assetTag)
            put("status", status)
            put("note", note ?: "")
            put("event_id", randomId())
            put("context", buildJsonObject {
                put("product", product ?: "")
                put("qty", qty)
                put("ticketId", ticketId ?: "")
                put("user", s?.username ?: "stockit-system")
                put("user_email", s?.email ?: "")
            })
        }.toString()
    }

    /**
     * POST JSON vers n8n, signé : `X-StockIT-Timestamp` + `X-StockIT-Signature` (HMAC-SHA256 du
     * timestamp et du corps envoyé, cf. [WebhookSigner]). Le secret n'est ajouté qu'ici, au moment de
     * l'envoi — jamais dans ce qui est mis en file d'attente. Compatibilité : tant que les workflows
     * n8n vérifient encore l'ancien champ, `X-App-Secret` et `secret` (si [StockItConfig.webhookLegacyBodySecret]) sont envoyés.
     */
    internal suspend fun signedPost(url: String, body: String): HttpResponse {
        val secret = StockItConfig.stockitWebhookSecret
        val sent = if (secret.isNotBlank() && StockItConfig.webhookLegacyBodySecret) withLegacySecret(body, secret) else body
        val ts = (nowMs() / 1000).toString()
        return Http.client.post(url) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json")
            if (secret.isNotBlank()) {
                header("X-App-Secret", secret)
                header("X-StockIT-Timestamp", ts)
                header("X-StockIT-Signature", WebhookSigner.sign(secret, ts, sent))
            }
            setBody(sent)
        }
    }

    private fun withLegacySecret(body: String, secret: String): String = runCatching {
        val o = Http.json.parseToJsonElement(body).jsonObject
        JsonObject(o + ("secret" to JsonPrimitive(secret))).toString()
    }.getOrDefault(body)

    /** POST JSON brut ; utilisé par la file d'attente. success=false si HTTP ≠ 2xx ou réponse {ok:false}. */
    internal suspend fun postJson(url: String, body: String): OpResult = if (StockItConfig.testMode) OpResult(true, "test_mode") else runCatching {
        val resp = signedPost(url, body)
        val ok = resp.status.isSuccess() && !reportsFailure(resp.bodyAsText())
        OpResult(ok, "HTTP ${resp.status.value}")
    }.getOrElse { OpResult(false, it::class.simpleName ?: "network_error") }

    /** Accusé n8n explicite `{"ok": false}`. */
    private fun reportsFailure(text: String): Boolean = runCatching {
        (Http.json.parseToJsonElement(text).jsonObject["ok"] as? JsonPrimitive)?.booleanOrNull == false
    }.getOrDefault(false)

    @Throws(Throwable::class)
    suspend fun sendReport(user: String?, email: String?, barcode: String?, message: String?): OpResult {
        if (StockItConfig.testMode) return OpResult(true, "test_mode")
        val url = StockItConfig.stockitWebhookUrl
        if (url.isBlank()) return OpResult(false, "webhook_not_configured")
        return runCatching {
            val body = buildJsonObject {
                put("user", user ?: "")
                put("email", email ?: "")
                put("barcode", barcode ?: "")
                put("message", message ?: "")
                put("event_id", randomId())
            }.toString()
            val resp = signedPost(url, body)
            if (resp.status.isSuccess()) OpResult(true, "HTTP ${resp.status.value}")
            else OpResult(false, "HTTP ${resp.status.value}")
        }.getOrElse { OpResult(false, it::class.simpleName ?: "network_error") }
    }
}
