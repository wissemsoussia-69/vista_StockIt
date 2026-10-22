package com.example.stockit.shared.network

import com.example.stockit.shared.tr
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
 * Slack. Ordre de priorité :
 *   1. Relais n8n `STOCKIT_SLACK_WEBHOOK_URL` (le bot « alerts » poste dans #etx-alerts)
 *      Payload : { type, message, user, user_email, source, timestamp, secret } — champs requis : type, message.
 *   2. Incoming Webhook Slack `SLACK_WEBHOOK_URL`
 *   3. Bot `SLACK_BOT_TOKEN` (chat.postMessage)
 */
object SlackNotifier {

    private fun webhookUsable(url: String): Boolean =
        url.isNotBlank() &&
            (url.startsWith("http://") || url.startsWith("https://")) &&
            !url.contains("REPLACE") && !url.contains("REMPLACE")

    /** Envoi d'un message (type déduit de l'emoji en tête). Ne lève jamais. */
    @Throws(Throwable::class)
    suspend fun send(message: String): OpResult {
        if (StockItConfig.testMode) return OpResult(true, "test_mode")
        if (webhookUsable(StockItConfig.stockitSlackWebhookUrl)) return sendViaRelay(typeOf(message), message)
        val url = StockItConfig.slackWebhookUrl
        if (!webhookUsable(url)) {
            return if (StockItConfig.slackBotToken.isNotBlank()) postAsBot(message) else OpResult(false, "slack_not_configured")
        }
        return runCatching {
            val body = buildJsonObject { put("text", message) }.toString()
            val resp = Http.client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            OpResult(resp.status.isSuccess(), "HTTP ${resp.status.value}")
        }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }
    }

    /**
     * Message de test (bouton « Tester Slack ») : ignore le mode test et utilise le relais n8n.
     * success=true si n8n répond { ok: true }.
     */
    @Throws(Throwable::class)
    suspend fun sendTest(user: String?): OpResult {
        val msg = "ℹ️ [StockIT iOS] Test de connexion Slack" + (user?.let { " par $it" } ?: "") + " — merci d'ignorer."
        return if (webhookUsable(StockItConfig.stockitSlackWebhookUrl)) sendViaRelay("info", msg)
        else OpResult(false, tr("STOCKIT_SLACK_WEBHOOK_URL non configuré", "STOCKIT_SLACK_WEBHOOK_URL not configured"))
    }

    /** Type d'alerte attendu par le workflow n8n, déduit de l'emoji du message. */
    internal fun typeOf(message: String): String = when {
        message.startsWith(":rotating_light:") || message.startsWith("🚨") -> "alert"
        message.startsWith(":x:") || message.startsWith(":warning:") || message.startsWith("⚠️") -> "warning"
        message.startsWith(":white_check_mark:") || message.startsWith(":package:") || message.startsWith("✅") -> "success"
        else -> "info"
    }

    private suspend fun sendViaRelay(type: String, message: String): OpResult = runCatching {
        val s = com.example.stockit.shared.StockIt.sessionOrNull
        val body = buildJsonObject {
            put("type", type)
            put("message", message)
            put("user", s?.username ?: "stockit-system")
            put("user_email", s?.email ?: "")
            put("source", "ios")
            put("timestamp", com.example.stockit.shared.platform.formatEpoch(
                com.example.stockit.shared.platform.nowMs(), "yyyy-MM-dd'T'HH:mm:ssZ"))
            put("secret", StockItConfig.stockitWebhookSecret)
        }.toString()
        val resp = Http.client.post(StockItConfig.stockitSlackWebhookUrl) {
            contentType(ContentType.Application.Json)
            if (StockItConfig.stockitWebhookSecret.isNotBlank()) header("X-App-Secret", StockItConfig.stockitWebhookSecret)
            setBody(body)
        }
        val text = resp.bodyAsText()
        val ok = resp.status.isSuccess() && text.replace(" ", "").contains("\"ok\":true")
        OpResult(ok, if (ok) tr("Message envoyé", "Message sent") else "HTTP ${resp.status.value} ${text.trimForLog(200)}")
    }.getOrElse { OpResult(false, "${it::class.simpleName}: ${it.message}") }

    /** Envoi via le bot Slack (chat.postMessage) sur #etx-alerts. */
    @Throws(Throwable::class)
    suspend fun postAsBot(message: String, channel: String = StockItConfig.slackChannelId): OpResult {
        if (StockItConfig.testMode) return OpResult(true, "test_mode")
        val token = StockItConfig.slackBotToken
        if (token.isBlank()) return OpResult(false, "bot_token_missing")
        return runCatching {
            val body = buildJsonObject {
                put("channel", channel)
                put("text", message)
            }.toString()
            val resp = Http.client.post("https://slack.com/api/chat.postMessage") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val text = resp.bodyAsText()
            OpResult(resp.status.isSuccess() && text.contains("\"ok\":true"), text.trimForLog())
        }.getOrElse { OpResult(false, it.message) }
    }
}
