package com.example.stockit.shared.security

/** Bounds for values coming from users, scanners, OCR or a language model (threat model T-10, T-15, T-22). */
object InputRules {
    /** Largest single stock movement accepted (+ or −). */
    const val MAX_STOCK_DELTA = 1_000
    /** Largest quantity a product can hold. */
    const val MAX_STOCK_QUANTITY = 1_000_000
    /** OCR text sent to the model is truncated beyond this size. */
    const val MAX_OCR_CHARS = 8_000

    private val BARCODE = Regex("""^[A-Za-z0-9][A-Za-z0-9 \-_.:/+#]{1,63}$""")

    /** Codes 2-64 characters, letters/digits and common separators — no markup, no control characters. */
    fun isPlausibleBarcode(code: String): Boolean = BARCODE.matches(code)

    /** Keeps the first [max] characters of untrusted text and removes control characters (except new lines). */
    fun clip(text: String, max: Int): String =
        text.take(max).filter { it == '\n' || it == '\t' || !it.isISOControl() }
}

/** Jira issue keys (« SD-1234 »). Anything else is never placed in a REST path (T-12). */
object JiraKeys {
    const val NO_TICKET = "NO-TICKET"
    private val KEY = Regex("""^[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,8}$""")

    fun isValid(key: String): Boolean = KEY.matches(key)

    fun isValidOrNoTicket(key: String): Boolean = key == NO_TICKET || isValid(key)

    /** Trimmed, upper-cased key, or null if it is not a Jira key. */
    fun normalize(key: String): String? = key.trim().uppercase().takeIf(::isValid)
}
