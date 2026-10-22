package com.example.stockit.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

internal actual fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) { block() }

private fun formatter(pattern: String) = NSDateFormatter().apply {
    dateFormat = pattern
    locale = NSLocale.currentLocale
}

actual fun formatEpoch(epochMs: Long, pattern: String): String =
    formatter(pattern).stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

actual fun parseDateToEpoch(text: String, pattern: String): Long? =
    formatter(pattern).dateFromString(text)?.let { (it.timeIntervalSince1970 * 1000).toLong() }
