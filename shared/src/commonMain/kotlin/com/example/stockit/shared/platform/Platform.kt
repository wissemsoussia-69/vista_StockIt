package com.example.stockit.shared.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * Points d'ancrage spécifiques à chaque plateforme (Android / iOS).
 */

/** Crée un client HTTP Ktor avec le moteur natif (OkHttp sur Android, NSURLSession sur iOS). */
internal expect fun createPlatformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/** Formate un epoch (ms) avec un motif type SimpleDateFormat ("dd/MM/yyyy HH:mm"), fuseau local. */
expect fun formatEpoch(epochMs: Long, pattern: String): String

/** Parse une date selon un motif (fuseau local). Retourne null si invalide. */
expect fun parseDateToEpoch(text: String, pattern: String): Long?

/** Horloge murale en millisecondes. */
fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
