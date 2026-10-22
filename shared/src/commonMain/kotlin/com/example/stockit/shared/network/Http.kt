package com.example.stockit.shared.network

import com.example.stockit.shared.platform.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object Http {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Client par défaut (timeouts alignés sur les OkHttpClient Android). */
    val client: HttpClient by lazy { create(connectMs = 15_000, readMs = 60_000) }

    fun create(connectMs: Long, readMs: Long): HttpClient = createPlatformHttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = connectMs
            requestTimeoutMillis = readMs + connectMs
            socketTimeoutMillis = readMs
        }
    }
}

internal fun String.trimForLog(max: Int = 300): String = if (length > max) take(max) + "…" else this

internal fun JsonObject.str(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val v = (e as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
    return if (v.isEmpty() || v.equals("null", ignoreCase = true)) null else v
}

internal fun JsonObject.int(key: String, default: Int): Int {
    val p = this[key] as? JsonPrimitive ?: return default
    return p.intOrNull ?: p.contentOrNull?.toDoubleOrNull()?.toInt() ?: default
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Retire les balises ```json … ``` qu'un LLM peut ajouter autour d'un JSON. */
internal fun stripCodeFence(raw: String): String {
    var clean = raw.trim()
    if (clean.startsWith("```")) {
        val nl = clean.indexOf('\n')
        if (nl > 0) clean = clean.substring(nl + 1)
        if (clean.endsWith("```")) clean = clean.dropLast(3)
        clean = clean.trim()
    }
    return clean
}

/** choices[0].message.content d'une réponse OpenAI-compatible (Cimpress / Portkey). */
internal fun extractOpenAiContent(payload: String): String? = runCatching {
    val root = Http.json.parseToJsonElement(payload).jsonObject
    val msg = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
        ?: return@runCatching null
    when (val c = msg["content"]) {
        is JsonPrimitive -> c.contentOrNull
        // Certains providers renvoient content: [{type:"text", text:"…"}]
        is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        else -> null
    }
}.getOrNull()

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
