package com.example.stockit.util;

import com.example.stockit.shared.config.StockItConfig;
import com.example.stockit.shared.security.WebhookSigner;

import java.util.HashMap;
import java.util.Map;

/**
 * Sécurité des webhooks n8n côté Android (TM-03, TM-04, TM-21) :
 *  - chaque POST est signé : {@code X-StockIT-Timestamp} +
 *    {@code X-StockIT-Signature: sha256=HMAC-SHA256(secret, "<ts>.<corps>")} ;
 *  - le secret n'est JAMAIS stocké dans la file d'attente (ajouté et signé à l'envoi) ;
 *  - un secret vide ne part jamais (échec fermé) ;
 *  - les renvois attendent un délai croissant (30 s, 1 min, 2 min… plafond 6 h).
 */
public final class WebhookSecurity {

    private static final long MAX_BACKOFF_MS = 6 * 3_600_000L;
    private static final long BASE_BACKOFF_MS = 30_000L;

    private WebhookSecurity() {}

    /**
     * @return true si le secret doit être envoyé dans le corps (compatibilité n8n,
     *         flag STOCKIT_WEBHOOK_LEGACY_BODY_SECRET). Un secret vide ne part jamais.
     */
    public static boolean shouldSendBodySecret(String secret) {
        if (secret == null || secret.trim().isEmpty()) return false; // TM-03 : échec fermé
        return StockItConfig.INSTANCE.getWebhookLegacyBodySecret();
    }

    /**
     * En-têtes d'authentification/intégrité du POST webhook.
     *
     * @param secret secret partagé (vide → aucun en-tête d'auth : échec fermé)
     * @param timestampUnix secondes Unix (envoyées telles quelles au récepteur)
     * @param body corps JSON envoyé (couvert par la signature)
     */
    public static Map<String, String> headers(String secret, String timestampUnix, String body) {
        Map<String, String> h = new HashMap<>();
        if (secret == null || secret.trim().isEmpty()) return h; // TM-03 : jamais d'auth bidon
        h.put("X-App-Secret", secret);
        h.put("X-StockIT-Timestamp", timestampUnix);
        h.put("X-StockIT-Signature", WebhookSigner.INSTANCE.sign(secret, timestampUnix, body));
        return h;
    }

    /**
     * Retire le champ « secret » d'un corps JSON mis en file d'attente. Un corps
     * illisible (donc incontrôlable) est remplacé par « {} » : jamais conservé tel quel.
     */
    public static String stripSecret(String jsonBody) {
        if (jsonBody == null) return "{}";
        try {
            org.json.JSONObject o = new org.json.JSONObject(jsonBody);
            o.remove("secret");
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Délai avant un renvoi après un échec. 30 s, 1 min, 2 min… plafonné à 6 h.
     *
     * @param attempts nombre d'échecs déjà enregistrés pour cet appel (≥ 1)
     */
    public static long backoffMs(int attempts) {
        int n = Math.max(1, attempts);
        // 30 s << (n-1), plafonné à 6 h (les tentatives > 11 restent au plafond).
        long ms = BASE_BACKOFF_MS;
        for (int i = 1; i < n && ms < MAX_BACKOFF_MS; i++) ms <<= 1;
        return Math.min(ms, MAX_BACKOFF_MS);
    }
}
