package com.example.stockit.shared.network

import com.example.stockit.shared.StockIt
import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.OpResult
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
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
                put("secret", StockItConfig.stockitWebhookSecret)
            }.toString()
            val resp = Http.client.post(url) {
                contentType(ContentType.Application.Json)
                if (StockItConfig.stockitWebhookSecret.isNotBlank()) header("X-App-Secret", StockItConfig.stockitWebhookSecret)
                setBody(body)
            }
            OpResult(resp.status.isSuccess(), resp.bodyAsText().trimForLog())
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
            put("secret", StockItConfig.stockitWebhookSecret)
            put("context", buildJsonObject {
                put("product", product ?: "")
                put("qty", qty)
                put("ticketId", ticketId ?: "")
                put("user", s?.username ?: "stockit-system")
                put("user_email", s?.email ?: "")
            })
        }.toString()
    }

    /** POST JSON brut ; utilisé par la file d'attente. success=false si HTTP ≠ 2xx ou réponse {ok:false}. */
    internal suspend fun postJson(url: String, body: String): OpResult = if (StockItConfig.testMode) OpResult(true, "test_mode") else runCatching {
        val resp = Http.client.post(url) {
            contentType(ContentType.Application.Json)
            if (StockItConfig.stockitWebhookSecret.isNotBlank()) header("X-App-Secret", StockItConfig.stockitWebhookSecret)
            setBody(body)
        }
        val text = resp.bodyAsText()
        val okFlag = !text.replace(" ", "").contains("\"ok\":false")
        OpResult(resp.status.isSuccess() && okFlag, "HTTP ${resp.status.value} ${text.trimForLog(200)}")
    }.getOrElse { OpResult(false, it.message ?: it::class.simpleName) }

    @Throws(Throwable::class)
    suspend fun sendReport(user: String?, email: String?, barcode: String?, message: String?): OpResult {
        if (StockItConfig.testMode) return OpResult(true, "test_mode")
        val url = StockItConfig.stockitWebhookUrl
        val secret = StockItConfig.stockitWebhookSecret
        if (url.isBlank()) return OpResult(false, "webhook_not_configured")
        return runCatching {
            val body = buildJsonObject {
                put("user", user ?: "")
                put("email", email ?: "")
                put("barcode", barcode ?: "")
                put("message", message ?: "")
                put("secret", secret)
            }.toString()
            val resp = Http.client.post(url) {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json")
                if (secret.isNotBlank()) header("X-App-Secret", secret)
                setBody(body)
            }
            val text = resp.bodyAsText()
            if (resp.status.isSuccess()) OpResult(true, text)
            else OpResult(false, "HTTP ${resp.status.value} ${text.trimForLog()}")
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }
}
