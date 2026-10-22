package com.example.stockit.util;

import android.text.TextUtils;
import android.util.Log;

import com.example.stockit.BuildConfig;
import com.example.stockit.shared.config.StockItConfig;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT — Alertes Slack (#etx-alerts). Même ordre de priorité que l'app iOS :
 *   1. Relais n8n {@code STOCKIT_SLACK_WEBHOOK_URL} (le bot « alerts » poste dans le canal)
 *   2. Incoming Webhook {@code SLACK_WEBHOOK_URL}
 *   3. Bot {@code SLACK_BOT_TOKEN} (chat.postMessage)
 * Respecte le mode test (aucun message envoyé).
 */
public final class SlackNotifier {

    private static final String TAG = "SlackNotifier";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private SlackNotifier() {}

    public interface Callback {
        void onResult(boolean success, String detail);
    }

    public static void send(final String message) {
        send(message, null);
    }

    public static void send(final String message, final Callback cb) {
        if (StockItConfig.INSTANCE.getTestMode()) {
            Log.i(TAG, "Mode test : Slack non envoyé — " + message);
            if (cb != null) cb.onResult(true, "test_mode");
            return;
        }
        new Thread(() -> {
            String[] r = deliver(message);
            if (cb != null) cb.onResult("ok".equals(r[0]), r[1]);
        }, "slack-notifier").start();
    }

    private static boolean usable(String url) {
        return !TextUtils.isEmpty(url)
                && (url.startsWith("http://") || url.startsWith("https://"))
                && !url.contains("REPLACE") && !url.contains("REMPLACE");
    }

    /** @return {"ok"|"ko", détail}. */
    private static String[] deliver(String message) {
        try {
            String relay = SharedBridge.slackRelayUrl();
            if (usable(relay)) {
                SessionManager s = SessionManager.get(null);
                JSONObject o = new JSONObject();
                o.put("type", typeOf(message));
                o.put("message", message);
                o.put("user", s != null && s.getUsername() != null ? s.getUsername() : "stockit-system");
                o.put("user_email", s != null && s.getEmail() != null ? s.getEmail() : "");
                o.put("source", "android");
                o.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
                o.put("secret", SharedBridge.webhookSecret());
                Request.Builder rb = new Request.Builder().url(relay).post(RequestBody.create(o.toString(), JSON));
                if (!TextUtils.isEmpty(SharedBridge.webhookSecret())) rb.header("X-App-Secret", SharedBridge.webhookSecret());
                try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    boolean ok = resp.isSuccessful() && text.replace(" ", "").contains("\"ok\":true");
                    Log.i(TAG, "Slack relais n8n HTTP " + resp.code() + " ok=" + ok);
                    if (ok) return new String[]{"ok", "relay"};
                }
            }
            String url = BuildConfig.SLACK_WEBHOOK_URL;
            if (usable(url)) {
                JSONObject o = new JSONObject();
                o.put("text", message);
                Request request = new Request.Builder().url(url).post(RequestBody.create(o.toString(), JSON)).build();
                try (Response resp = CLIENT.newCall(request).execute()) {
                    if (resp.isSuccessful()) return new String[]{"ok", "HTTP " + resp.code()};
                }
            }
            String token = SharedBridge.slackBotToken();
            if (!TextUtils.isEmpty(token)) {
                JSONObject o = new JSONObject();
                o.put("channel", SharedBridge.slackChannel());
                o.put("text", message);
                Request request = new Request.Builder().url("https://slack.com/api/chat.postMessage")
                        .header("Authorization", "Bearer " + token)
                        .post(RequestBody.create(o.toString(), JSON)).build();
                try (Response resp = CLIENT.newCall(request).execute()) {
                    String text = resp.body() != null ? resp.body().string() : "";
                    boolean ok = resp.isSuccessful() && text.contains("\"ok\":true");
                    return new String[]{ok ? "ok" : "ko", ok ? "bot" : text};
                }
            }
            Log.w(TAG, "Slack non configuré — message ignoré : " + message);
            return new String[]{"ko", "slack_not_configured"};
        } catch (Throwable t) {
            Log.e(TAG, "Slack error (silencée)", t);
            return new String[]{"ko", t.getClass().getSimpleName() + ": " + t.getMessage()};
        }
    }

    static String typeOf(String m) {
        if (m == null) return "info";
        if (m.startsWith(":rotating_light:") || m.startsWith("🚨")) return "alert";
        if (m.startsWith(":x:") || m.startsWith(":warning:") || m.startsWith("⚠️")) return "warning";
        if (m.startsWith(":white_check_mark:") || m.startsWith(":package:") || m.startsWith("✅")
                || m.startsWith(":shopping_trolley:")) return "success";
        return "info";
    }
}
