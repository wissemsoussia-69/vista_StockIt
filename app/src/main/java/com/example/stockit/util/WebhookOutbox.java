package com.example.stockit.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.stockit.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT — Webhooks n8n des mouvements de stock (identique iOS) :
 *   - {@code stockit/event}        : entrée / sortie (barcode, product, qty, type, …)
 *   - {@code stockit/asset-status} : IN_STOCK / OUT
 * En cas d'échec (réseau, workflow inactif) l'appel est mis en file d'attente
 * persistante et renvoyé automatiquement ({@link #flush}).
 */
public final class WebhookOutbox {

    private static final String TAG = "WebhookOutbox";
    private static final String PREFS = "stockit_outbox";
    private static final String KEY = "items";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build();

    public interface CountCallback { void onCount(int pending); }

    private WebhookOutbox() {}

    /** Mouvement de stock → event + asset-status (no-op si mode test ou webhooks absents). */
    public static void notifyStockEvent(Context ctx, String assetTag, String product, String type,
                                        int qty, @Nullable String reason, @Nullable String ticketId) {
        if (qty <= 0) return;
        final Context app = ctx.getApplicationContext();
        if (SharedBridge.isTestMode(app)) return;
        final String tag = TextUtils.isEmpty(assetTag) ? "UNKNOWN" : assetTag;
        final String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());
        SessionManager s = SessionManager.get(app);
        final String user = !TextUtils.isEmpty(s.getUsername()) ? s.getUsername() : "stockit-system";
        final String email = !TextUtils.isEmpty(s.getEmail()) ? s.getEmail() : com.example.stockit.BuildConfig.STOCKIT_DEFAULT_EMAIL;
        final String secret = SharedBridge.webhookSecret();

        EXEC.execute(() -> {
            try {
                String eventUrl = SharedBridge.eventWebhookUrl();
                if (!TextUtils.isEmpty(eventUrl)) {
                    JSONObject o = new JSONObject();
                    o.put("barcode", tag); o.put("product", product == null ? "?" : product);
                    o.put("qty", qty); o.put("type", type);
                    o.put("reason", reason == null ? "" : reason);
                    o.put("ticket_id", ticketId == null ? "" : ticketId);
                    o.put("timestamp", ts); o.put("user", user); o.put("user_email", email);
                    o.put("source", "android"); o.put("secret", secret);
                    sendOrQueue(app, eventUrl, o.toString());
                }
                String assetUrl = SharedBridge.assetStatusWebhookUrl();
                if (!TextUtils.isEmpty(assetUrl)) {
                    JSONObject ctxObj = new JSONObject();
                    ctxObj.put("product", product == null ? "" : product);
                    ctxObj.put("qty", qty);
                    ctxObj.put("ticketId", ticketId == null ? "" : ticketId);
                    ctxObj.put("user", user);
                    ctxObj.put("user_email", email);
                    JSONObject o = new JSONObject();
                    o.put("source", "stockit-android"); o.put("at", ts); o.put("assetTag", tag);
                    o.put("status", "IN".equals(type) ? "IN_STOCK" : "OUT");
                    o.put("note", reason == null ? "" : reason);
                    o.put("secret", secret); o.put("context", ctxObj);
                    sendOrQueue(app, assetUrl, o.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "notifyStockEvent", e);
            }
        });
    }

    private static void sendOrQueue(Context app, String url, String body) {
        String err = post(url, body);
        if (err != null) {
            Log.w(TAG, "Webhook KO, mis en file : " + err);
            enqueue(app, url, body, err);
        }
    }

    /** @return null si succès, sinon le détail de l'erreur. */
    @Nullable
    private static String post(String url, String body) {
        try {
            Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(body, JSON));
            String secret = SharedBridge.webhookSecret();
            if (!TextUtils.isEmpty(secret)) rb.header("X-App-Secret", secret);
            try (Response r = CLIENT.newCall(rb.build()).execute()) {
                String text = r.body() != null ? r.body().string() : "";
                boolean okFlag = !text.replace(" ", "").contains("\"ok\":false");
                return (r.isSuccessful() && okFlag) ? null : "HTTP " + r.code();
            }
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static synchronized JSONArray load(Context app) {
        try {
            return new JSONArray(prefs(app).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static synchronized void save(Context app, JSONArray arr) {
        prefs(app).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void enqueue(Context app, String url, String body, String err) {
        try {
            JSONArray arr = load(app);
            JSONObject o = new JSONObject();
            o.put("url", url); o.put("body", body); o.put("error", err); o.put("at", System.currentTimeMillis());
            arr.put(o);
            // Borne la file (évite une croissance infinie hors ligne).
            while (arr.length() > 200) arr.remove(0);
            save(app, arr);
        } catch (Exception e) {
            Log.e(TAG, "enqueue", e);
        }
    }

    public static int pendingCount(Context ctx) {
        return load(ctx.getApplicationContext()).length();
    }

    /** Renvoie les appels en attente ; callback (thread principal) avec le nombre restant. */
    public static void flush(Context ctx, @Nullable CountCallback cb) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            JSONArray arr = load(app);
            JSONArray remaining = new JSONArray();
            boolean test = SharedBridge.isTestMode(app);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (test) { remaining.put(o); continue; }
                String err = post(o.optString("url"), o.optString("body"));
                if (err != null) {
                    try { o.put("error", err); } catch (Exception ignored) {}
                    remaining.put(o);
                }
            }
            save(app, remaining);
            final int n = remaining.length();
            if (cb != null) new Handler(Looper.getMainLooper()).post(() -> cb.onCount(n));
        });
    }

    /** Évite l'avertissement "unused" : BuildConfig reste la source des clés côté Java. */
    @SuppressWarnings("unused")
    private static final boolean DEBUG = BuildConfig.DEBUG;
}
