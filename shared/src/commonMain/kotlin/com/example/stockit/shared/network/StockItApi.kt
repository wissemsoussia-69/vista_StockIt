package com.example.stockit.shared.network

import com.example.stockit.shared.config.StockItConfig
import com.example.stockit.shared.model.ChatMessage
import com.example.stockit.shared.model.Claim
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Backend réclamations/alertes (Retrofit `ApiService` Android : claims, messages, alerts).
 * Injecte `Authorization: Bearer <token>` si un fournisseur de token est branché (Auth0).
 */
object StockItApi {

    /** Fournisseur d'access token (Auth0). Branché par l'app iOS/Android. */
    var accessTokenProvider: (suspend () -> String?)? = null

    private val client by lazy { Http.create(connectMs = 5_000, readMs = 20_000) }

    private fun url(path: String): String? {
        val base = StockItConfig.apiBaseUrl.trim()
        if (base.isBlank()) return null
        return base.trimEnd('/') + "/" + path
    }

    private suspend fun auth(): String? = runCatching { accessTokenProvider?.invoke() }.getOrNull()

    internal suspend fun getClaims(): List<Claim>? {
        val u = url("claims") ?: return null
        val token = auth()
        val resp = client.get(u) { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
        if (!resp.status.isSuccess()) return null
        return Http.json.decodeFromString(ListSerializer(Claim.serializer()), resp.bodyAsText())
    }

    internal suspend fun addClaim(claim: Claim): Boolean {
        val u = url("claims") ?: return false
        val token = auth()
        return client.post(u) {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Http.json.encodeToString(Claim.serializer(), claim))
        }.status.isSuccess()
    }

    internal suspend fun getMessages(claimId: Long): List<ChatMessage>? {
        val u = url("claims/$claimId/messages") ?: return null
        val token = auth()
        val resp = client.get(u) { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
        if (!resp.status.isSuccess()) return null
        return Http.json.decodeFromString(ListSerializer(ChatMessage.serializer()), resp.bodyAsText())
    }

    internal suspend fun addMessage(claimId: Long, msg: ChatMessage): Boolean {
        val u = url("claims/$claimId/messages") ?: return false
        val token = auth()
        return client.post(u) {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Http.json.encodeToString(ChatMessage.serializer(), msg))
        }.status.isSuccess()
    }

    internal suspend fun sendAlert(data: JsonElement): Boolean {
        if (StockItConfig.testMode) return true
        val u = url("alerts") ?: return false
        val token = auth()
        return client.post(u) {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(data.toString())
        }.status.isSuccess()
    }
}
