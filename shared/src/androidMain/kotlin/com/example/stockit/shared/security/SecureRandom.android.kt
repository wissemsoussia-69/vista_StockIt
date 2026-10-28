package com.example.stockit.shared.security

import java.security.SecureRandom

private val rng = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { rng.nextBytes(it) }
