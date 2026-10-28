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
 * Toujours authentifié : `Authorization: Bearer <token Auth0>` ; sans token valide, aucune requête n'est envoyée.
 */
object StockItApi {

    /** Fournisseur d'access token (Auth0). Branché par l'app iOS/Android. */
    var accessTokenProvider: (suspend () -> String?)? = null

    private val defaultClient by lazy { Http.create(connectMs = 5_000, readMs = 20_000) }
    private val client get() = if (Http.testEngine != null) Http.client else defaultClient

    private fun url(path: String): String? {
        val base = StockItConfig.apiBaseUrl.trim()
        if (base.isBlank() || !isAllowedBase(base)) return null
        return base.trimEnd('/') + "/" + path
    }

    /** HTTPS obligatoire ; HTTP toléré uniquement vers la machine locale (simulateur). */
    internal fun isAllowedBase(base: String): Boolean {
        val b = base.lowercase()
        if (b.startsWith("https://")) return true
        return LOCAL_HOSTS.any { b.startsWith("http://$it/") || b.startsWith("http://$it:") || b == "http://$it" }
    }
    private val LOCAL_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

    /** Access token courant, ou null : sans token, AUCUN appel n'est émis (fail closed). */
    private suspend fun token(): String? = runCatching { accessTokenProvider?.invoke() }.getOrNull()?.takeIf { it.isNotBlank() }

    internal suspend fun getClaims(): List<Claim>? {
        val u = url("claims") ?: return null
        val token = token() ?: return null
        val resp = client.get(u) { header("Authorization", "Bearer $token") }
        if (!resp.status.isSuccess()) return null
        return Http.json.decodeFromString(ListSerializer(Claim.serializer()), resp.bodyAsText())
    }

    internal suspend fun addClaim(claim: Claim): Boolean {
        val u = url("claims") ?: return false
        val token = token() ?: return false
        return client.post(u) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Http.json.encodeToString(Claim.serializer(), claim))
        }.status.isSuccess()
    }

    internal suspend fun getMessages(claimId: Long): List<ChatMessage>? {
        val u = url("claims/$claimId/messages") ?: return null
        val token = token() ?: return null
        val resp = client.get(u) { header("Authorization", "Bearer $token") }
        if (!resp.status.isSuccess()) return null
        return Http.json.decodeFromString(ListSerializer(ChatMessage.serializer()), resp.bodyAsText())
    }

    internal suspend fun addMessage(claimId: Long, msg: ChatMessage): Boolean {
        val u = url("claims/$claimId/messages") ?: return false
        val token = token() ?: return false
        return client.post(u) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Http.json.encodeToString(ChatMessage.serializer(), msg))
        }.status.isSuccess()
    }

    internal suspend fun sendAlert(data: JsonElement): Boolean {
        if (StockItConfig.testMode) return true
        val u = url("alerts") ?: return false
        val token = token() ?: return false
        return client.post(u) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(data.toString())
        }.status.isSuccess()
    }
}
