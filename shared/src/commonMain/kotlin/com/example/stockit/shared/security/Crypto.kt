package com.example.stockit.shared.security

/** Cryptographically secure random bytes (SecureRandom on JVM, SecRandomCopyBytes on Apple). */
internal expect fun secureRandomBytes(size: Int): ByteArray

/** SHA-256 (FIPS 180-4). Pure Kotlin so that it runs unchanged on iOS and on the JVM. */
object Sha256 {
    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    fun digest(data: ByteArray): ByteArray {
        val h = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19).map { it.toInt() }.toIntArray()
        val bitLen = data.size.toLong() * 8
        val padLen = ((data.size + 9 + 63) / 64) * 64
        val msg = data.copyOf(padLen)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padLen - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (block in 0 until padLen / 64) {
            val o = block * 64
            for (t in 0 until 16) {
                w[t] = ((msg[o + 4 * t].toInt() and 0xff) shl 24) or ((msg[o + 4 * t + 1].toInt() and 0xff) shl 16) or
                    ((msg[o + 4 * t + 2].toInt() and 0xff) shl 8) or (msg[o + 4 * t + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return ByteArray(32) { i -> (h[i / 4] ushr (24 - 8 * (i % 4))).toByte() }
    }
}

/** HMAC (RFC 2104) over SHA-256. */
object Hmac {
    private const val BLOCK = 64

    fun sha256(key: ByteArray, data: ByteArray): ByteArray {
        val k = (if (key.size > BLOCK) Sha256.digest(key) else key).copyOf(BLOCK)
        val inner = ByteArray(BLOCK) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(BLOCK) { (k[it].toInt() xor 0x5c).toByte() }
        return Sha256.digest(outer + Sha256.digest(inner + data))
    }
}

/** PBKDF2 (RFC 8018) with HMAC-SHA256. */
object Pbkdf2 {
    fun sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        require(iterations > 0 && dkLen > 0)
        val out = ByteArray(dkLen)
        var block = 1
        var pos = 0
        while (pos < dkLen) {
            val idx = byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
            var u = Hmac.sha256(password, salt + idx)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = Hmac.sha256(password, u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, pos, 0, minOf(t.size, dkLen - pos))
            pos += t.size
            block++
        }
        return out
    }
}

/**
 * Local (offline) account passwords. Stored as `pbkdf2-sha256$<iterations>$<salt hex>$<hash hex>`.
 * Anything else in the column (legacy plaintext, empty value of SSO rows) never verifies.
 */
object PasswordHasher {
    const val PREFIX = "pbkdf2-sha256"
    const val DEFAULT_ITERATIONS = 120_000

    fun hash(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = secureRandomBytes(16)
        val dk = Pbkdf2.sha256(password.encodeToByteArray(), salt, iterations, 32)
        return "$PREFIX$$iterations$${salt.toHex()}$${dk.toHex()}"
    }

    fun verify(password: String, stored: String): Boolean {
        if (password.isEmpty()) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = parts[2].hexToBytesOrNull() ?: return false
        val expected = parts[3].hexToBytesOrNull()?.takeIf { it.size == 32 } ?: return false
        return constantTimeEquals(Pbkdf2.sha256(password.encodeToByteArray(), salt, iterations, 32), expected)
    }
}

/**
 * Webhook request signature: `sha256=<hex HMAC-SHA256(secret, "<timestamp>.<body>")>`.
 * Sent with `X-StockIT-Timestamp` so the receiver (n8n) can reject forged and replayed calls.
 */
object WebhookSigner {
    fun sign(secret: String, timestamp: String, body: String): String =
        "sha256=" + Hmac.sha256(secret.encodeToByteArray(), "$timestamp.$body".encodeToByteArray()).toHex()
}

fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) { append(HEX[(b.toInt() ushr 4) and 0xf]); append(HEX[b.toInt() and 0xf]) }
}

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0 || isEmpty()) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = HEX.indexOf(this[2 * i].lowercaseChar()); val lo = HEX.indexOf(this[2 * i + 1].lowercaseChar())
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** Random identifier (hex) — webhook idempotency keys. */
internal fun randomId(bytes: Int = 16): String = secureRandomBytes(bytes).toHex()
