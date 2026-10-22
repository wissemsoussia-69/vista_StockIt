package com.example.stockit.shared.network

import com.example.stockit.shared.tr
import com.example.stockit.shared.config.StockItConfig
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class TextResult(val text: String?, val error: String?)

/**
 * Résultat d'identification visuelle.
 * [confidence] : 0-100 renvoyé par Claude (-1 si inconnu). [serial] : numéro de série lu sur la photo.
 */
data class VisionResult(
    val name: String?, val error: String?, val trace: String, val needsOnDeviceFallback: Boolean,
    val confidence: Int = -1, val serial: String? = null,
)

/** Détail de la réponse vision de Claude. */
data class VisionParse(val name: String?, val confidence: Int, val serial: String?)

/**
 * Passerelles IA — portage de util/GeminiGatewayClient.java + appels LLM texte
 * (TicketMatcher, parsers, assistant, rapport).
 *
 * Vision, cascade identique à Android :
 *   1. Cimpress Gateway (OpenAI-compatible, Bearer)
 *   2. Portkey
 *   3. Gemini direct (si clé "AIza…")
 *   4. Hugging Face vit-base-patch16-224
 *   5. (plateforme) MLKit Android / Apple Vision iOS — hors réseau
 */
object AIGateway {

    const val VISION_PROMPT =
        "Tu es l'assistant d'inventaire IT de Vistaprint. Identifie l'équipement informatique principal de la photo. " +
            "Lis les logos, étiquettes et inscriptions visibles pour trouver la marque et le modèle exacts.\n" +
            "Réponds UNIQUEMENT avec ce JSON, sans texte autour :\n" +
            "{\"type\": \"Souris\", \"brand\": \"Logitech\", \"model\": \"MX Master 3S\", \"name\": \"Souris Logitech MX Master 3S\", " +
            "\"serial\": \"2215LZ0ABC8\", \"confidence\": 85}\n" +
            "Règles : type en français parmi Clavier, Souris, Écran, Ordinateur portable, PC de bureau, Casque, " +
            "Station d'accueil, Webcam, Téléphone, Tablette, Câble, Chargeur, Imprimante, Routeur, Disque, Autre. " +
            "brand et model : null si illisibles, ne devine pas. name = type + marque + modèle (sans les null). " +
            "serial : numéro de série UNIQUEMENT s'il est lisible (après S/N, SN, Serial, Service Tag), sinon null — ne l'invente jamais. " +
            "confidence : entier 0-100 = ta certitude sur name (100 = logo et modèle clairement lisibles, " +
            "50 = type sûr mais marque/modèle incertains, < 30 = photo floue ou objet ambigu). " +
            "Si aucun équipement IT n'est visible : {\"type\": null, \"brand\": null, \"model\": null, \"name\": null, \"serial\": null, \"confidence\": 0}."

    private val visionClient by lazy { Http.create(connectMs = 15_000, readMs = 45_000) }

    // ------------------------------------------------------------------ texte

    /** Appel Cimpress Gateway /chat/completions (Claude). */
    @Throws(Throwable::class)
    suspend fun cimpressChat(prompt: String, maxTokens: Int = 1000, system: String? = null): TextResult {
        if (!StockItConfig.isCimpressConfigured) {
            if (StockItConfig.isClaudeConfigured) return claudeMessages(prompt, maxTokens, system, null)
            return TextResult(null, "ia_not_configured")
        }
        return runCatching {
            val body = buildJsonObject {
                put("model", StockItConfig.cimpressVisionModel)
                put("max_tokens", maxTokens)
                putJsonArray("messages") {
                    if (system != null) addJsonObject { put("role", "system"); put("content", system) }
                    addJsonObject { put("role", "user"); put("content", prompt) }
                }
            }.toString()
            val resp = Http.client.post(StockItConfig.gatewayUrl.trimEnd('/') + "/chat/completions") {
                header("Authorization", "Bearer ${StockItConfig.cimpressGatewayKey}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val payload = resp.bodyAsText()
            if (!resp.status.isSuccess()) TextResult(null, "HTTP ${resp.status.value}")
            else TextResult(extractOpenAiContent(payload), null)
        }.getOrElse { TextResult(null, it.message ?: it::class.simpleName) }
    }

    /**
     * Claude direct — POST https://api.anthropic.com/v1/messages (option B).
     * `imageB64` : JPEG base64 optionnel (vision).
     */
    @Throws(Throwable::class)
    suspend fun claudeMessages(prompt: String, maxTokens: Int, system: String?, imageB64: String?): TextResult {
        val key = StockItConfig.claudeApiKey
        if (key.isBlank()) return TextResult(null, "claude_not_configured")
        return runCatching {
            val body = buildJsonObject {
                put("model", StockItConfig.claudeModel)
                put("max_tokens", maxTokens)
                if (system != null) put("system", system)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            if (imageB64 != null) addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64"); put("media_type", "image/jpeg"); put("data", imageB64)
                                }
                            }
                            addJsonObject { put("type", "text"); put("text", prompt) }
                        }
                    }
                }
            }.toString()
            val resp = visionClient.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val payload = resp.bodyAsText()
            if (!resp.status.isSuccess()) TextResult(null, "ClaudeHTTP${resp.status.value} ${shortBody(payload)}")
            else TextResult(extractClaudeText(payload), null)
        }.getOrElse { TextResult(null, it.message ?: it::class.simpleName) }
    }

    internal fun extractClaudeText(payload: String): String? = runCatching {
        (Http.json.parseToJsonElement(payload).jsonObject["content"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.takeIf { o -> o.str("type") == "text" }?.get("text") as? JsonPrimitive }
            ?.joinToString("") { it.contentOrNull ?: "" }
    }.getOrNull()

    /** Gemini generateContent (texte). */
    @Throws(Throwable::class)
    suspend fun geminiText(prompt: String, model: String = "gemini-1.5-pro"): TextResult {
        val key = StockItConfig.geminiApiKey
        if (!looksLikeGoogleApiKey(key)) return TextResult(null, "gemini_key_missing")
        return runCatching {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } }
                }
            }.toString()
            val resp = Http.client.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
                parameter("key", key)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val payload = resp.bodyAsText()
            if (!resp.status.isSuccess()) TextResult(null, "HTTP ${resp.status.value}")
            else TextResult(extractGeminiText(payload), null)
        }.getOrElse { TextResult(null, it.message) }
    }

    /**
     * Assistant conversationnel (AIChatActivity). Android utilise le SDK Gemini
     * (gemini-1.5-pro) ; ici : Gemini si clé Google valide, sinon Cimpress Gateway.
     */
    @Throws(Throwable::class)
    suspend fun ask(prompt: String): TextResult {
        val c = cimpressChat(prompt, maxTokens = 800)
        if (c.text != null) return c
        val g = geminiText(prompt)
        if (g.text != null) return g
        return TextResult(null, "IA: ${c.error} / Gemini: ${g.error}")
    }

    // ------------------------------------------------------------------ vision

    /**
     * Identifie l'objet IT principal d'une photo JPEG (encodée base64 pour simplifier
     * l'interop Swift : `data.base64EncodedString()`).
     */
    @Throws(Throwable::class)
    suspend fun identify(jpegBase64: String): VisionResult {
        if (jpegBase64.isBlank()) return VisionResult(null, "empty_image", "", false)
        if (!StockItConfig.isAIConfigured) {
            return VisionResult(null,
                tr("Claude non configuré : renseignez CIMPRESS_GATEWAY_KEY (ou CLAUDE_API_KEY) dans .env.sh",
                    "Claude not configured: set CIMPRESS_GATEWAY_KEY (or CLAUDE_API_KEY) in .env.sh"), "", false)
        }
        val trace = StringBuilder()
        var raw: String? = null

        // 1) Claude via la passerelle Cimpress
        if (StockItConfig.isCimpressConfigured) {
            raw = tryOpenAiVision(
                url = StockItConfig.gatewayUrl.trimEnd('/') + "/chat/completions",
                headers = mapOf("Authorization" to "Bearer ${StockItConfig.cimpressGatewayKey}"),
                model = StockItConfig.cimpressVisionModel,
                b64 = jpegBase64, label = "Cimpress", trace = trace, withTemperature = false,
            )
        }
        // 2) Claude direct (api.anthropic.com)
        if (raw.isNullOrBlank() && StockItConfig.isClaudeConfigured) {
            val r = claudeMessages(VISION_PROMPT, 300, null, jpegBase64)
            raw = r.text
            if (raw.isNullOrBlank()) trace.append(r.error ?: tr("Claude: réponse vide", "Claude: empty response")).append("; ")
        }
        if (raw.isNullOrBlank()) return VisionResult(null, tr("Claude indisponible — $trace", "Claude unavailable — $trace"), trace.toString(), false)

        val parsed = parseVision(raw)
        val name = parsed.name
            ?: return VisionResult(null, tr("Aucun équipement IT reconnu sur la photo", "No IT equipment recognized in the photo"), trace.toString(), false, parsed.confidence, null)
        return VisionResult(name, null, trace.toString(), false, parsed.confidence, parsed.serial)
    }

    /** Extrait le nom depuis le JSON de Claude (tolère du texte autour ou une réponse non-JSON). */
    internal fun parseVisionName(raw: String): String? = parseVision(raw).name

    /** Nom + confiance (0-100, -1 si absente) + numéro de série (null si illisible). */
    fun parseVision(raw: String): VisionParse {
        val clean = stripCodeFence(raw)
        val json = Regex("""\{[\s\S]*}""").find(clean)?.value
        if (json != null) {
            val o = runCatching { Http.json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            if (o != null) {
                val conf = o.int("confidence", -1).let { if (it < 0) -1 else it.coerceAtMost(100) }
                val serial = normalizeSerial(o.str("serial"))
                o.str("name")?.let { return VisionParse(it.trim().take(80), conf, serial) }
                val parts = listOfNotNull(o.str("type"), o.str("brand"), o.str("model"))
                return VisionParse(if (parts.isEmpty()) null else parts.joinToString(" ").take(80), conf, serial)
            }
        }
        val t = clean.replace(Regex("[\"'`*]"), "").lines().firstOrNull { it.isNotBlank() }?.trim()
        return VisionParse(t?.takeIf { it.isNotEmpty() && !it.equals("null", true) }?.take(80), -1, null)
    }

    /** Nettoie un numéro de série (préfixes S/N, espaces) ; null s'il n'est pas plausible. */
    fun normalizeSerial(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
            .replace(Regex("""^(?i)(s/?n|serial( number| no\.?)?|service tag|n° ?de ?série)\s*[:#]?\s*"""), "")
            .replace(" ", "").uppercase()
        if (s.length !in 4..30 || !s.any { it.isLetterOrDigit() }) return null
        if (s == "NULL" || s == "N/A" || s == "NA" || s == "INCONNU" || s == "UNKNOWN") return null
        return s.filter { it.isLetterOrDigit() || it == '-' }.takeIf { it.length >= 4 }
    }

    private suspend fun tryOpenAiVision(
        url: String, headers: Map<String, String>, model: String, b64: String,
        label: String, trace: StringBuilder, withTemperature: Boolean,
    ): String? = runCatching {
        // NB : pas de "temperature" pour Claude Opus via Cimpress (dépréciée).
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 300)
            if (withTemperature) put("temperature", 0.1)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", VISION_PROMPT) }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$b64") }
                        }
                    }
                }
            }
        }.toString()
        val resp = visionClient.post(url) {
            headers.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val payload = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            trace.append("${label}HTTP${resp.status.value}[${shortBody(payload)}]; ")
            null
        } else {
            extractOpenAiContent(payload)
        }
    }.getOrElse { trace.append("${label}IO(${it.message}); "); null }

    private suspend fun tryGeminiVision(b64: String, apiKey: String, trace: StringBuilder): String? = runCatching {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject { put("text", VISION_PROMPT) }
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") { put("maxOutputTokens", 32); put("temperature", 0.1) }
        }.toString()
        val resp = visionClient.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val payload = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            trace.append("GeminiHTTP${resp.status.value}; "); null
        } else extractGeminiText(payload)
    }.getOrElse { trace.append("GeminiIO(${it.message}); "); null }

    private suspend fun tryHuggingFace(b64: String, token: String, trace: StringBuilder): String? = runCatching {
        val bytes = Base64.decode(b64)
        val resp = visionClient.post("https://api-inference.huggingface.co/models/google/vit-base-patch16-224") {
            header("Authorization", "Bearer $token")
            header("x-wait-for-model", "true")
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
        val payload = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            trace.append("HFHTTP${resp.status.value}; "); null
        } else {
            val arr = Http.json.parseToJsonElement(payload) as? JsonArray
            (arr?.firstOrNull() as? JsonObject)?.str("label")
        }
    }.getOrElse { trace.append("HFIO(${it.message}); "); null }

    // ------------------------------------------------------------------ helpers

    fun looksLikeGoogleApiKey(k: String?): Boolean = k != null && k.startsWith("AIza") && k.length >= 35

    internal fun extractGeminiText(payload: String): String? = runCatching {
        Http.json.parseToJsonElement(payload).jsonObject["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
            ?.joinToString("") { it.contentOrNull ?: "" }
    }.getOrNull()

    /** Nettoie la réponse (guillemets, points, 2 mots max). */
    fun clean(s: String): String {
        var t = s.trim().replace(Regex("[\"'`.*]"), "").replace('\n', ' ').trim()
        val parts = t.split(Regex("\\s+"))
        if (parts.size > 2) t = parts[0] + " " + parts[1]
        return t
    }

    /** Traduit un label anglais (HF / MLKit / Apple Vision) en libellé court français. */
    fun mapToFrench(raw: String?): String {
        if (raw == null) return "Objet"
        val l = raw.lowercase()
        return when {
            "keyboard" in l || "keypad" in l -> "Clavier"
            "mouse" in l -> "Souris"
            "laptop" in l || "notebook" in l -> "Ordinateur portable"
            "monitor" in l || "screen" in l || "crt" in l || "display" in l -> "Écran"
            "desktop" in l || "pc" in l || "computer" in l -> "PC de bureau"
            "printer" in l -> "Imprimante"
            "scanner" in l -> "Scanner"
            "cell" in l || "phone" in l || "mobile" in l -> "Téléphone"
            "tablet" in l || "ipad" in l -> "Tablette"
            "headphone" in l || "earphone" in l || "headset" in l -> "Casque"
            "router" in l || "modem" in l -> "Routeur"
            "cable" in l || "cord" in l -> "Câble"
            "hard disc" in l || "hard disk" in l || "ssd" in l || "drive" in l -> "Disque"
            "usb" in l || "flash" in l -> "Clé USB"
            else -> {
                val words = raw.split(Regex("[,_\\s]+")).filter { it.isNotEmpty() }.take(2)
                if (words.isEmpty()) "Objet"
                else words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            }
        }
    }

    private fun shortBody(s: String): String {
        var flat = s.replace(Regex("\\s+"), " ").trim()
        runCatching {
            val o = Http.json.parseToJsonElement(s).jsonObject
            val m = o.str("message") ?: o.obj("error")?.str("message")
            if (m != null) flat = m
        }
        return flat.trimForLog(120)
    }
}
