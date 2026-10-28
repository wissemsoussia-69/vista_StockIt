package com.example.stockit.security;

import com.example.stockit.util.WebhookSecurity;

import org.junit.Test;

import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TM-03 / TM-04 / TM-21 — webhooks n8n : échec fermé sur secret vide, signature HMAC
 * (timestamp + corps), secret jamais persisté dans la file d'attente, backoff des renvois.
 */
public class WebhookSecurityTest {

    private static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(data.getBytes("UTF-8"))) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    public void requestsCarryTimestampAndHmacSignature() throws Exception {
        String ts = "1700000000";
        String body = "{\"qty\":1}";
        Map<String, String> h = WebhookSecurity.headers("s3cr3t", ts, body);
        assertEquals(ts, h.get("X-StockIT-Timestamp"));
        String sig = h.get("X-StockIT-Signature");
        assertNotNull("signature HMAC manquante", sig);
        assertTrue(sig.startsWith("sha256="));
        assertEquals("sha256=" + hmacHex("s3cr3t", ts + "." + body), sig);
    }

    @Test
    public void signatureCoversTimestampAndBody() throws Exception {
        String ts = "1700000000";
        String body = "{\"a\":1}";
        String sig = WebhookSecurity.headers("k", ts, body).get("X-StockIT-Signature");
        assertNotNull(sig);
        assertFalse(sig.equals(WebhookSecurity.headers("k", "1700000001", body).get("X-StockIT-Signature")));
        assertFalse(sig.equals(WebhookSecurity.headers("k", ts, "{\"a\":2}").get("X-StockIT-Signature")));
        assertFalse(sig.equals(WebhookSecurity.headers("other", ts, body).get("X-StockIT-Signature")));
    }

    @Test
    public void emptySecretFailsClosed() {
        // TM-03 : un secret vide ne doit jamais partir dans le corps ni authentifier quoi que ce soit.
        assertFalse(WebhookSecurity.shouldSendBodySecret(""));
        assertFalse(WebhookSecurity.shouldSendBodySecret(null));
        Map<String, String> h = WebhookSecurity.headers("", "1700000000", "{}");
        assertNull("aucun header d'auth avec un secret vide", h.get("X-App-Secret"));
        assertNull(h.get("X-StockIT-Signature"));
        assertNull(WebhookSecurity.headers(null, "1", "{}").get("X-StockIT-Signature"));
    }

    @Test
    public void queuedBodiesNeverContainTheSharedSecret() {
        String stored = WebhookSecurity.stripSecret("{\"qty\":1,\"secret\":\"top-secret\",\"type\":\"IN\"}");
        assertFalse("le secret ne doit pas être persisté dans la file", stored.contains("top-secret"));
        assertTrue(stored.contains("\"qty\":1"));
    }

    @Test
    public void malformedQueuedBodyIsDroppedRatherThanKept() {
        String stored = WebhookSecurity.stripSecret("not-json-secret=oops");
        assertFalse(stored.contains("secret"));
    }

    @Test
    public void failedDeliveriesBackOffExponentially() {
        assertEquals(30_000L, WebhookSecurity.backoffMs(1));
        assertEquals(60_000L, WebhookSecurity.backoffMs(2));
        assertEquals(120_000L, WebhookSecurity.backoffMs(3));
        // plafond : 6 h
        assertEquals(6 * 3_600_000L, WebhookSecurity.backoffMs(11));
        assertEquals(6 * 3_600_000L, WebhookSecurity.backoffMs(50));
    }
}
