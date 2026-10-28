package com.example.stockit.shared

import com.example.stockit.shared.security.Hmac
import com.example.stockit.shared.security.PasswordHasher
import com.example.stockit.shared.security.Pbkdf2
import com.example.stockit.shared.security.Sha256
import com.example.stockit.shared.security.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Known-answer tests: the expected values come from the published standards, not from this code. */
class CryptoTest {

    @Test
    fun sha256MatchesFips180Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.digest(ByteArray(0)).toHex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.digest("abc".encodeToByteArray()).toHex())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex(),
        )
        // 1 000 000 × 'a' : exercises many blocks and the length padding.
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.digest(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex(),
        )
    }

    @Test
    fun hmacSha256MatchesRfc4231() {
        // Test case 1
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Hmac.sha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).toHex(),
        )
        // Test case 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Hmac.sha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
        // Test case 6 : key longer than the block size
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            Hmac.sha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun pbkdf2Sha256MatchesPublishedVectors() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        assertEquals("120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b", Pbkdf2.sha256(p, s, 1, 32).toHex())
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", Pbkdf2.sha256(p, s, 2, 32).toHex())
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", Pbkdf2.sha256(p, s, 4096, 32).toHex())
        // dkLen > 32 : two output blocks
        assertEquals(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            Pbkdf2.sha256("passwordPASSWORDpassword".encodeToByteArray(), "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray(), 4096, 40).toHex(),
        )
    }

    @Test
    fun passwordHashIsSaltedAndVerifiable() {
        val h1 = PasswordHasher.hash("S3cure-pass!", iterations = 1000)
        val h2 = PasswordHasher.hash("S3cure-pass!", iterations = 1000)
        assertTrue(h1.startsWith("pbkdf2-sha256$1000$"), h1)
        assertFalse(h1.contains("S3cure-pass!"))
        assertNotEquals(h1, h2, "random salt: two hashes of the same password must differ")
        assertTrue(PasswordHasher.verify("S3cure-pass!", h1))
        assertTrue(PasswordHasher.verify("S3cure-pass!", h2))
        assertFalse(PasswordHasher.verify("s3cure-pass!", h1))
        assertFalse(PasswordHasher.verify("", h1))
    }

    @Test
    fun passwordVerifyRejectsLegacyAndMalformedValues() {
        // A plaintext value left in the database must never be accepted as a hash.
        assertFalse(PasswordHasher.verify("admin123", "admin123"))
        assertFalse(PasswordHasher.verify("sso-auth0", "sso-auth0"))
        assertFalse(PasswordHasher.verify("x", ""))
        assertFalse(PasswordHasher.verify("x", "pbkdf2-sha256$0$00$00"))
        assertFalse(PasswordHasher.verify("x", "pbkdf2-sha256\$abc\$zz\$zz"))
    }
}
