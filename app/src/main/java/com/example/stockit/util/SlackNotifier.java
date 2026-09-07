package com.example.stockit.util;

import android.text.TextUtils;
import android.util.Log;

import com.example.stockit.BuildConfig;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT PFE — Envoi d'alertes vers Slack via Incoming Webhook.
 * Canal cible : #alertes-logistique
 *
 * L'URL du webhook est injectée via BuildConfig.SLACK_WEBHOOK_URL.
 */
public final class SlackNotifier {

    private static final String TAG = "SlackNotifier";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private SlackNotifier() {}

    /** Callback simple pour retourner le résultat sur le thread principal si nécessaire. */
    public interface Callback {
        void onResult(boolean success, String detail);
    }

    /** Envoi asynchrone d'un simple message texte sur le canal configuré. */
    public static void send(final String message) {
        send(message, null);
    }

    /** Envoi asynchrone avec callback. */
    public static void send(final String message, final Callback cb) {
        final String url = BuildConfig.SLACK_WEBHOOK_URL;
        // Sécurité : garde stricte pour tout URL non http(s) ou placeholder.
        if (TextUtils.isEmpty(url)
                || !(url.startsWith("http://") || url.startsWith("https://"))
                || url.contains("REPLACE") || url.contains("REMPLACE")) {
            Log.w(TAG, "Slack webhook non configuré (url=" + url + ") — message ignoré : " + message);
            if (cb != null) cb.onResult(false, "webhook_missing");
            return;
        }

        new Thread(() -> {
            try {
                String json = "{\"text\":" + jsonQuote(message) + "}";
                Request request = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(json, JSON))
                        .build();
                try (Response resp = CLIENT.newCall(request).execute()) {
                    boolean ok = resp.isSuccessful();
                    Log.i(TAG, "Slack HTTP " + resp.code() + " ok=" + ok);
                    if (cb != null) cb.onResult(ok, "HTTP " + resp.code());
                }
            } catch (Throwable t) {
                // On attrape TOUT (y compris RuntimeException / OOM) pour ne pas kill le process
                Log.e(TAG, "Slack error (silencée)", t);
                if (cb != null) cb.onResult(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "slack-notifier").start();
    }

    private static String jsonQuote(String s) {
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
}
