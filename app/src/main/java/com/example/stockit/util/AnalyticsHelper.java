package com.example.stockit.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.stockit.model.AnalyticsEvent;
import com.example.stockit.model.AppDatabase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AnalyticsHelper {

    private static final String TAG = "AnalyticsHelper";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "analytics-io");
        t.setDaemon(true);
        return t;
    });

    private AnalyticsHelper() {}


    public static void logLogin(@NonNull Context ctx, @NonNull String method, @Nullable String role) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("method", method);
        if (role != null) p.put("role", role);
        log(ctx, "login", p);
    }

    public static void logScanSuccess(@NonNull Context ctx, @Nullable String productLabel, long latencyMs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_label", productLabel == null ? "" : productLabel);
        p.put("latency_ms", latencyMs);
        log(ctx, "scan_success", p);
    }

    public static void logScanFailed(@NonNull Context ctx, @NonNull String errorType, long latencyMs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("error_type", errorType);
        p.put("latency_ms", latencyMs);
        log(ctx, "scan_failed", p);
    }

    public static void logEntryValidated(@NonNull Context ctx, @NonNull String productName,
                                         int qty, boolean withPo, long durationMs) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("product_name", productName);
        p.put("qty", qty);
        p.put("with_po", withPo);
        p.put("duration_ms", durationMs);
        log(ctx, "entry_validated", p);
    }

    public static void logExitRecorded(@NonNull Context ctx, @NonNull String mode,
                                       int qty, boolean ticketMatched) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("mode", mode);          // "auto_claude" | "manual" | "list" | "no_ticket"
        p.put("qty", qty);
        p.put("ticket_matched", ticketMatched);
        log(ctx, "exit_recorded", p);
    }

    public static void logAiCall(@NonNull Context ctx, @NonNull String service,
                                 boolean success, long latencyMs, @Nullable String errorTag) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("service", service);    // "cimpress_vision" | "cimpress_chat" | "cimpress_matcher"
        p.put("success", success);
        p.put("latency_ms", latencyMs);
        if (errorTag != null) p.put("error", errorTag);
        log(ctx, "ai_call", p);
    }

    public static void log(@NonNull Context ctx, @NonNull String eventName,
                           @Nullable Map<String, Object> properties) {
        final Context appCtx = ctx.getApplicationContext();
        final String user = safeUsername(appCtx);
        final String json = toJson(properties);
        final long ts = System.currentTimeMillis();

        IO.execute(() -> {
            try {
                AnalyticsEvent ev = new AnalyticsEvent(eventName, user, json, ts);
                AppDatabase.getInstance(appCtx).analyticsEventDao().insert(ev);
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "logged " + eventName + " " + json);
                }
            } catch (Throwable t) {
                Log.w(TAG, "insert failed for " + eventName + " - " + t.getMessage());
            }
        });
    }


    private static String safeUsername(Context ctx) {
        try {
            SessionManager s = SessionManager.get(ctx);
            String u = s.getUsername();
            return TextUtils.isEmpty(u) ? "anonymous" : u;
        } catch (Throwable ignored) {
            return "anonymous";
        }
    }

    static String toJson(@Nullable Map<String, Object> props) {
        if (props == null || props.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(props.size() * 24).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonQuote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null)                       sb.append("null");
            else if (v instanceof Boolean)       sb.append(v.toString());
            else if (v instanceof Number)        sb.append(v.toString());
            else                                 sb.append(jsonQuote(String.valueOf(v)));
        }
        return sb.append('}').toString();
    }

    static String jsonQuote(String s) {
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
