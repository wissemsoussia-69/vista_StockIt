package com.example.stockit.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) { block() }

actual fun formatEpoch(epochMs: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))

actual fun parseDateToEpoch(text: String, pattern: String): Long? = try {
    SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }.parse(text)?.time
} catch (_: Exception) {
    null
}
