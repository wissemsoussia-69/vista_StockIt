package com.example.stockit.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.stockit.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT — Client webhook interne (n8n / {@code automation.vista.io}).
 *
 * <p>Utilisé pour envoyer :</p>
 * <ul>
 *   <li>Des rapports de stock automatiques (remplace le path SendGrid direct).</li>
 *   <li>Des notifications e-mail (l'automation orchestre l'envoi côté serveur).</li>
 *   <li>Des alertes ad-hoc (code de vérif, erreurs de sync…).</li>
 * </ul>
 *
 * <p>Configuration : {@code STOCKIT_WEBHOOK_URL} + {@code STOCKIT_WEBHOOK_SECRET}
 * dans {@code set_env.ps1} / {@code .env.sh} → injectés via {@link BuildConfig}.
 * Aucun secret n'est stocké en dur dans le code source.</p>
 *
 * <p>Basé sur OkHttp (déjà présent). Pas de dépendance à Volley.</p>
 */
public final class StockItReporter {

    private static final String TAG = "StockItReporter";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private StockItReporter() {}

    /** Callback simple pour connaître le succès et le body de réponse. */
    public interface ResultCallback {
        void onResult(boolean success, @Nullable String bodyOrError);
    }

    /**
     * Overload haut niveau : envoie un événement au workflow n8n en récupérant
     * automatiquement l'utilisateur connecté depuis {@link SessionManager}.
     * Utilisé par tous les scénarios de notification pour déclencher
     * l'e-mail (le workflow n8n orchestre l'envoi côté serveur).
     *
     * @param context Contexte Android (pour lire la session).
     * @param event   Code court identifiant l'événement (ex. "STOCK_LOW",
     *                "SCAN_KIT_INCOMPLETE") — envoyé dans le champ {@code barcode}
     *                pour que le workflow n8n route vers le bon template mail.
     * @param message Corps du message (sujet + détails libres).
     */
    public static void sendEvent(@NonNull Context context,
                                 @NonNull String event,
                                 @NonNull String message) {
        SessionManager session = SessionManager.get(context);
        String user  = session.getUsername();
        String email = session.getEmail();
        // Fallback : si l'utilisateur n'est pas encore connecté (worker de fond,
        // service Firebase…), on cible l'e-mail par défaut du projet.
        if (TextUtils.isEmpty(user))  user  = "stockit-system";
        if (TextUtils.isEmpty(email)) email = "wissem.soussia@vista.com";
        sendReport(user, email, event, message, null);
    }

    /**
     * Overload avec destinataire explicite : chaque scénario métier peut cibler
     * un e-mail dédié (manager stock, support, achats, audit…) sans dépendre de
     * la session courante. Utilisé par la refonte "un mail personnalisé par
     * événement" — voir sites d'appel dans ScanAssetActivity, ScanOutActivity,
     * MainController, etc.
     *
     * @param context        Contexte Android (pour lire la session).
     * @param subjectSuffix  Texte qui vient après {@code "StockIT — "} dans le
     *                       sujet du mail final composé par n8n. Ex :
     *                       {@code "Alerte stock bas : Laptop Dell"}. Envoyé
     *                       dans le champ JSON {@code barcode} du webhook.
     * @param message        Corps libre du mail (sera précédé automatiquement
     *                       par le préambule {@code "User : … Event : …"}).
     * @param recipientEmail Destinataire du mail (peut être une seule adresse).
     */
    public static void sendEvent(@NonNull Context context,
                                 @NonNull String subjectSuffix,
                                 @NonNull String message,
                                 @NonNull String recipientEmail) {
        SessionManager session = SessionManager.get(context);
        String user = session.getUsername();
        if (TextUtils.isEmpty(user)) user = "stockit-system";
        sendReport(user, recipientEmail, subjectSuffix, message, null);
    }

    /**
     * Envoie un rapport JSON au webhook StockIT. Non bloquant (OkHttp async).
     *
     * @param user     nom de l'utilisateur (peut être null)
     * @param email    e-mail destinataire (peut être null)
     * @param barcode  code produit / identifiant du sujet (peut être null)
     * @param message  message libre
     * @param cb       callback optionnel (invoqué sur un thread de fond OkHttp)
     */
    public static void sendReport(@Nullable String user,
                                  @Nullable String email,
                                  @Nullable String barcode,
                                  @Nullable String message,
                                  @Nullable ResultCallback cb) {
        final String url    = BuildConfig.STOCKIT_WEBHOOK_URL;
        final String secret = BuildConfig.STOCKIT_WEBHOOK_SECRET;

        if (TextUtils.isEmpty(url)) {
            Log.w(TAG, "STOCKIT_WEBHOOK_URL vide — envoi ignoré.");
            if (cb != null) cb.onResult(false, "webhook_not_configured");
            return;
        }

        String json = "{"
                + "\"user\":"    + jsonQuote(user)    + ","
                + "\"email\":"   + jsonQuote(email)   + ","
                + "\"barcode\":" + jsonQuote(barcode) + ","
                + "\"message\":" + jsonQuote(message) + ","
                + "\"secret\":"  + jsonQuote(secret)
                + "}";

        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .header("Accept", "application/json");
        // Le secret est aussi dans le body (compatibilité workflow n8n), mais on
        // le duplique en header pour permettre au workflow d'authentifier avant
        // de lire le corps (défense en profondeur).
        if (!TextUtils.isEmpty(secret)) {
            rb.header("X-App-Secret", secret);
        }

        CLIENT.newCall(rb.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Webhook StockIT réseau KO : " + e.getMessage());
                if (cb != null) cb.onResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            @Override public void onResponse(Call call, Response resp) throws IOException {
                String body = resp.body() != null ? resp.body().string() : "";
                Log.i(TAG, "Webhook StockIT HTTP " + resp.code() + " -> " + trimForLog(body));
                if (cb != null) {
                    if (resp.isSuccessful()) {
                        cb.onResult(true, body);
                    } else {
                        cb.onResult(false, "HTTP " + resp.code() + " " + trimForLog(body));
                    }
                }
                resp.close();
            }
        });
    }

    // ------------------------------------------------------------------
    private static String jsonQuote(@Nullable String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String trimForLog(@Nullable String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
