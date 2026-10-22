package com.example.stockit.util;

import android.text.TextUtils;
import android.util.Log;

import com.example.stockit.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * StockIT — Appel texte à Claude via la passerelle Cimpress (OpenAI-compatible).
 * Même configuration que l'app iOS : GATEWAY_URL + CIMPRESS_GATEWAY_KEY + CIMPRESS_VISION_MODEL.
 * Appel BLOQUANT : à exécuter hors du thread UI.
 */
public final class CimpressChat {

    private static final String TAG = "CimpressChat";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private CimpressChat() {}

    /** Résultat : soit text (succès), soit error. */
    public static final class Result {
        public final String text;
        public final String error;
        Result(String text, String error) { this.text = text; this.error = error; }
        public boolean ok() { return text != null && !text.trim().isEmpty(); }
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(BuildConfig.CIMPRESS_GATEWAY_KEY) && !TextUtils.isEmpty(BuildConfig.GATEWAY_URL);
    }

    /**
     * @param system   rôle système (peut être null)
     * @param prompt   message utilisateur
     * @param maxTokens limite de la réponse
     */
    public static Result chat(String system, String prompt, int maxTokens) {
        if (!isConfigured()) {
            return new Result(null, "cimpress_not_configured");
        }
        String model = TextUtils.isEmpty(BuildConfig.CIMPRESS_VISION_MODEL)
                ? "@Anthropic/eu.anthropic.claude-opus-4-8" : BuildConfig.CIMPRESS_VISION_MODEL;
        try {
            JSONArray messages = new JSONArray();
            if (system != null && !system.isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", system));
            }
            messages.put(new JSONObject().put("role", "user").put("content", prompt));
            // NB : pas de "temperature" (dépréciée pour Claude Opus via Cimpress).
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("messages", messages);

            Request req = new Request.Builder()
                    .url(BuildConfig.GATEWAY_URL.replaceAll("/$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + BuildConfig.CIMPRESS_GATEWAY_KEY)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response resp = CLIENT.newCall(req).execute()) {
                String payload = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    Log.w(TAG, "HTTP " + resp.code());
                    return new Result(null, "HTTP " + resp.code() + (resp.code() == 401 ? " (clé Cimpress invalide)" : ""));
                }
                JSONObject msg = new JSONObject(payload).getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                Object content = msg.opt("content");
                String text;
                if (content instanceof JSONArray) {
                    StringBuilder sb = new StringBuilder();
                    JSONArray parts = (JSONArray) content;
                    for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
                    text = sb.toString();
                } else {
                    text = msg.optString("content", null);
                }
                return new Result(text, text == null ? "llm_empty" : null);
            }
        } catch (Exception e) {
            Log.e(TAG, "chat failed", e);
            return new Result(null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Retire le markdown (**, #, `) que la bulle de chat n'affiche pas. */
    public static String plainText(String s) {
        if (s == null) return null;
        return s.replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .trim();
    }
}
