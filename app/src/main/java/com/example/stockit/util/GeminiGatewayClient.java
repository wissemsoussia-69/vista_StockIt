package com.example.stockit.util;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.example.stockit.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class GeminiGatewayClient {

    private static final String TAG = "ClaudeGateway";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final String PROMPT =
                        "Return ONLY the exact short name of the main IT object "
                    + "(e.g. 'Keyboard', 'Mouse', 'Monitor'). No sentence, no generic category "
                    + "such as 'IT object', maximum two words. Reply in English.";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    private GeminiGatewayClient() {}

    public interface Callback {
        void onResult(String name, String error);
    }

    public static void identify(final android.content.Context ctx,
                                final byte[] jpegBytes,
                                final Callback cb) {
        final long startNs = System.nanoTime();
        identify(jpegBytes, (name, error) -> {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            try {
                com.example.stockit.util.AnalyticsHelper.logAiCall(
                        ctx, "cimpress_vision",
                        error == null, latencyMs,
                        error);
            } catch (Throwable ignored) {
            }
            cb.onResult(name, error);
        });
    }

    public static void identify(final byte[] jpegBytes, final Callback cb) {
        if (jpegBytes == null || jpegBytes.length == 0) {
            cb.onResult(null, "empty_image");
            return;
        }

        new Thread(() -> {
            StringBuilder trace = new StringBuilder();

            String cimKey   = BuildConfig.CIMPRESS_GATEWAY_KEY;
            String cimUrl   = BuildConfig.GATEWAY_URL;
            String cimModel = BuildConfig.CIMPRESS_VISION_MODEL;

            if (TextUtils.isEmpty(cimKey) || TextUtils.isEmpty(cimUrl)) {
                cb.onResult(null, "Cimpress not configured (missing key or URL). "
                    + "Reload set_env.ps1 then rebuild.");
                return;
            }

            String r = tryCimpress(jpegBytes, cimUrl, cimKey, cimModel, trace);
            if (r != null) {
                cb.onResult(clean(r), null);
                return;
            }

            Log.w(TAG, "Cimpress failure - trace: " + trace);
            cb.onResult(null, "Cimpress AI service unavailable - " + trace);
        }, "vision-gateway").start();
    }


    private static String tryCimpress(byte[] jpeg, String baseUrl, String key,
                                      String model, StringBuilder trace) {
        try {
            String b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
            String body = "{\n" +
                    "  \"model\": " + jsonQuote(model) + ",\n" +
                    "  \"max_tokens\": 32,\n" +
                    "  \"messages\": [{\n" +
                    "    \"role\": \"user\",\n" +
                    "    \"content\": [\n" +
                    "      { \"type\": \"text\", \"text\": " + jsonQuote(PROMPT) + " },\n" +
                    "      { \"type\": \"image_url\", \"image_url\": { \"url\": \"data:image/jpeg;base64," + b64 + "\" } }\n" +
                    "    ]\n" +
                    "  }]\n" +
                    "}";

            Request req = new Request.Builder()
                    .url(baseUrl.replaceAll("/$", "") + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .post(RequestBody.create(body, JSON))
                    .build();

            try (Response resp = CLIENT.newCall(req).execute()) {
                String payload = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    Log.w(TAG, "Cimpress HTTP " + resp.code() + " -> " + trimLog(payload));
                    trace.append("CimpressHTTP").append(resp.code())
                         .append("[").append(shortBody(payload)).append("]; ");
                    return null;
                }
                String text = extractOpenAiContent(payload);
                Log.i(TAG, "Cimpress OK -> " + text);
                return text;
            }
        } catch (IOException e) {
            Log.e(TAG, "Cimpress IO", e);
            trace.append("CimpressIO(").append(e.getMessage()).append("); ");
            return null;
        }
    }


    private static String extractOpenAiContent(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);

            org.json.JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                org.json.JSONObject first = choices.optJSONObject(0);
                if (first != null) {
                    org.json.JSONObject msg = first.optJSONObject("message");
                    if (msg != null) {
                        Object content = msg.opt("content");
                        String parsed = parseContentObject(content);
                        if (parsed != null && !parsed.trim().isEmpty()) return parsed;
                    }
                }
            }

            Object direct = root.opt("content");
            String parsedDirect = parseContentObject(direct);
            if (parsedDirect != null && !parsedDirect.trim().isEmpty()) return parsedDirect;

            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String parseContentObject(Object content) {
        if (content == null || content == org.json.JSONObject.NULL) return null;
        if (content instanceof String) return (String) content;
        if (content instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof org.json.JSONObject) {
                    org.json.JSONObject o = (org.json.JSONObject) item;
                    String t = o.optString("text", "").trim();
                    if (!t.isEmpty()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(t);
                    }
                } else if (item instanceof String) {
                    String t = ((String) item).trim();
                    if (!t.isEmpty()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(t);
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("[\"'`.]", "");
        String[] parts = t.split("\\s+");
        if (parts.length > 2) t = parts[0] + " " + parts[1];
        return t;
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

    private static String trimLog(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    private static String shortBody(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").trim();
        int m = flat.indexOf("\"message\"");
        if (m >= 0) {
            int q1 = flat.indexOf('"', m + 9);
            int q2 = q1 >= 0 ? flat.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) return flat.substring(q1 + 1, q2);
        }
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }

}
