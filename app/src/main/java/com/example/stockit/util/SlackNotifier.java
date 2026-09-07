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

public final class SlackNotifier {

    private static final String TAG = "SlackNotifier";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_URL = "https://slack.com/api/chat.postMessage";
    private static final String DEFAULT_CHANNEL = "#stockit-etx-tunis";

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
        sendToChannel(resolveChannel(), message, cb);
    }

    public static void sendToChannel(final String channel, final String message, final Callback cb) {
        final String token = BuildConfig.SLACK_BOT_TOKEN;
        if (TextUtils.isEmpty(token) || !token.startsWith("xoxb-")) {
            Log.w(TAG, "Slack bot token not configured (SLACK_BOT_TOKEN empty or not in xoxb- format)");
            if (cb != null) cb.onResult(false, "bot_token_missing");
            return;
        }
        if (TextUtils.isEmpty(channel)) {
            Log.w(TAG, "Slack: channel not configured, message ignored: " + message);
            if (cb != null) cb.onResult(false, "channel_missing");
            return;
        }

        new Thread(() -> {
            try {
                String body = "{"
                        + "\"channel\":" + jsonQuote(channel) + ","
                        + "\"text\":" + jsonQuote(message)
                        + "}";
                Request request = new Request.Builder()
                        .url(API_URL)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .post(RequestBody.create(body, JSON))
                        .build();
                try (Response resp = CLIENT.newCall(request).execute()) {
                    String payload = resp.body() != null ? resp.body().string() : "";
                    boolean httpOk = resp.isSuccessful();
                    boolean apiOk = httpOk && payload.contains("\"ok\":true");
                    if (apiOk) {
                        Log.i(TAG, "Slack posted to " + channel);
                        if (cb != null) cb.onResult(true, "OK");
                    } else {
                        String err = extractField(payload, "error");
                        Log.w(TAG, "Slack failed: " + (err != null ? err : payload));
                        if ("not_in_channel".equals(err)) {
                            Log.w(TAG, "-> invite the bot with `/invite @alerts2` in " + channel);
                        }
                        if (cb != null) cb.onResult(false, err != null ? err : ("HTTP " + resp.code()));
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Slack error (suppressed)", t);
                if (cb != null) cb.onResult(false, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "slack-notifier").start();
    }

    private static String resolveChannel() {
        String configured = BuildConfig.SLACK_CHANNEL;
        return TextUtils.isEmpty(configured) ? DEFAULT_CHANNEL : configured;
    }

    private static String extractField(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
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
