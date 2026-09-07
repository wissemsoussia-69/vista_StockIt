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

public final class StockItReporter {

    private static final String TAG = "StockItReporter";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private StockItReporter() {}

    public interface ResultCallback {
        void onResult(boolean success, @Nullable String bodyOrError);
    }

    public static void sendEvent(@NonNull Context context,
                                 @NonNull String event,
                                 @NonNull String message) {
        SessionManager session = SessionManager.get(context);
        String user  = session.getUsername();
        String email = session.getEmail();
        if (TextUtils.isEmpty(user))  user  = "stockit-system";
        if (TextUtils.isEmpty(email)) email = "wissem.soussia@vista.com";
        sendReport(user, email, event, message, null);
    }

    public static void sendEvent(@NonNull Context context,
                                 @NonNull String subjectSuffix,
                                 @NonNull String message,
                                 @NonNull String recipientEmail) {
        sendEvent(context, subjectSuffix, message, recipientEmail, null);
    }

    public static void sendEvent(@NonNull Context context,
                                 @NonNull String subjectSuffix,
                                 @NonNull String message,
                                 @NonNull String recipientEmail,
                                 @Nullable ResultCallback cb) {
        SessionManager session = SessionManager.get(context);
        String user = session.getUsername();
        if (TextUtils.isEmpty(user)) user = "stockit-system";
        sendReport(user, recipientEmail, subjectSuffix, message, cb);
    }

    public static void sendReport(@Nullable String user,
                                  @Nullable String email,
                                  @Nullable String barcode,
                                  @Nullable String message,
                                  @Nullable ResultCallback cb) {
        sendReportToUrl(BuildConfig.STOCKIT_WEBHOOK_URL, user, email, barcode, message, cb);
    }

    public static void sendReportToUrl(@Nullable String url,
                                       @Nullable String user,
                                       @Nullable String email,
                                       @Nullable String barcode,
                                       @Nullable String message,
                                       @Nullable ResultCallback cb) {
        final String secret = BuildConfig.STOCKIT_WEBHOOK_SECRET;

        if (TextUtils.isEmpty(url)) {
            Log.w(TAG, "Webhook URL empty - send ignored.");
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
        if (!TextUtils.isEmpty(secret)) {
            rb.header("X-App-Secret", secret);
        }

        CLIENT.newCall(rb.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "StockIT webhook network failure: " + e.getMessage());
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


    private static final java.text.SimpleDateFormat ISO_8601;
    static {
        ISO_8601 = new java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        ISO_8601.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static void sendStockEvent(@Nullable Context context,
                                      @NonNull String barcode,
                                      @NonNull String product,
                                      int qty) {
        final String url = BuildConfig.STOCKIT_STOCK_EVENT_URL;
        if (TextUtils.isEmpty(url)) {
            Log.w(TAG, "STOCKIT_STOCK_EVENT_URL empty - stock event ignored.");
            return;
        }

        String userEmail = "wissem.soussia@vista.com";
        if (context != null) {
            try {
                String e = SessionManager.get(context).getEmail();
                if (!TextUtils.isEmpty(e)) userEmail = e;
            } catch (Throwable ignored) {}
        }

        final String timestamp;
        synchronized (ISO_8601) { timestamp = ISO_8601.format(new java.util.Date()); }

        String json = "{"
                + "\"barcode\":"    + jsonQuote(barcode)   + ","
                + "\"product\":"    + jsonQuote(product)   + ","
                + "\"qty\":"        + qty                  + ","
                + "\"timestamp\":"  + jsonQuote(timestamp) + ","
                + "\"user_email\":" + jsonQuote(userEmail)
                + "}";

        Request.Builder rb = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON))
                .header("Accept", "application/json");
        if (!TextUtils.isEmpty(BuildConfig.STOCKIT_WEBHOOK_SECRET)) {
            rb.header("X-App-Secret", BuildConfig.STOCKIT_WEBHOOK_SECRET);
        }

        CLIENT.newCall(rb.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "stock event network failure: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response resp) throws IOException {
                String body = resp.body() != null ? resp.body().string() : "";
                Log.i(TAG, "stock event HTTP " + resp.code() + " -> " + trimForLog(body));
                resp.close();
            }
        });
    }

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
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
