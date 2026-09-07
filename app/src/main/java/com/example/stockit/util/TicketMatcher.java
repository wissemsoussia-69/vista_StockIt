package com.example.stockit.util;

import android.text.TextUtils;
import android.util.Log;

import com.example.stockit.BuildConfig;
import com.example.stockit.model.JiraTicket;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class TicketMatcher {

    private static final String TAG = "TicketMatcher";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private TicketMatcher() {}

    public static class Assignment implements Serializable {
        public String ticketId;
        public int    qty;
        public String reason;
        public Assignment(String id, int q, String r) { ticketId = id; qty = q; reason = r; }
    }

    public interface Callback { void onResult(List<Assignment> assignments, String error); }

    public static void assign(final String equipmentName, final int quantity,
                              final List<JiraTicket> tickets, final Callback cb) {
        if (tickets == null || tickets.isEmpty()) {
            cb.onResult(new ArrayList<>(), "no_open_tickets");
            return;
        }
        if (quantity <= 0) {
            cb.onResult(new ArrayList<>(), "invalid_quantity");
            return;
        }

        new Thread(() -> {
            try {
                String prompt = buildPrompt(equipmentName, quantity, tickets);
                Log.i(TAG, "Prompt sent (" + prompt.length() + " chars)");

                String key = BuildConfig.CIMPRESS_GATEWAY_KEY;
                String url = BuildConfig.GATEWAY_URL;
                String model = BuildConfig.CIMPRESS_VISION_MODEL;
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(url)) {
                    cb.onResult(null, "cimpress_not_configured");
                    return;
                }

                String body = "{\n" +
                        "  \"model\": " + jsonQuote(model) + ",\n" +
                        "  \"max_tokens\": 1000,\n" +
                        "  \"messages\": [{\n" +
                        "    \"role\": \"user\",\n" +
                        "    \"content\": " + jsonQuote(prompt) + "\n" +
                        "  }]\n" +
                        "}";

                Request req = new Request.Builder()
                        .url(url.replaceAll("/$", "") + "/chat/completions")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .post(RequestBody.create(body, JSON))
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    String payload = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        Log.w(TAG, "Cimpress HTTP " + resp.code() + " -> " + trim(payload, 300));
                        cb.onResult(null, "HTTP " + resp.code());
                        return;
                    }
                    String content = extractOpenAiContent(payload);
                    Log.i(TAG, "LLM raw -> " + trim(content, 500));
                    List<Assignment> result = parseAssignments(content, quantity);
                    cb.onResult(result, null);
                }
            } catch (IOException e) {
                Log.e(TAG, "IO", e);
                cb.onResult(null, e.getMessage());
            } catch (Throwable t) {
                Log.e(TAG, "crash", t);
                cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "ticket-matcher").start();
    }

    private static String buildPrompt(String item, int qty, List<JiraTicket> tickets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Context: StockIT - IT equipment outbound.\n");
        sb.append("Scanned equipment: ").append(item).append("\n");
        sb.append("Quantity to release: ").append(qty).append("\n\n");
        sb.append("Open Jira tickets:\n");
        for (JiraTicket t : tickets) {
            sb.append("---\n");
            sb.append("ID       : ").append(t.key).append("\n");
            sb.append("Status   : ").append(nn(t.status)).append("\n");
            sb.append("Priority : ").append(nn(t.priority)).append("\n");
            sb.append("Due date : ").append(nn(t.dueDate)).append("\n");
            sb.append("Summary  : ").append(nn(t.summary)).append("\n");
            if (t.description != null && !t.description.isEmpty()) {
                String desc = t.description.length() > 400
                        ? t.description.substring(0, 400) + "..."
                        : t.description;
                sb.append("Description : ").append(desc).append("\n");
            }
        }
        sb.append("\nAnalyze descriptions and distribute quantity across tickets that seem");
        sb.append(" to need this equipment.\n");
        sb.append("Priority order:\n");
        sb.append(" 1. Priority Jira DESC (Highest / J1 > Lowest / J5)\n");
        sb.append(" 2. Due date ASC (nearest first)\n");
        sb.append(" 3. Otherwise order of appearance\n\n");
        sb.append("Return ONLY this strict JSON:\n");
        sb.append("[\n");
        sb.append("  { \"ticketId\": \"ETXTUN-42\", \"qty\": 3, \"reason\": \"1-2 short sentences explaining this ticket\" }\n");
        sb.append("]\n");
        sb.append("Rules:\n");
        sb.append(" - Assign ONLY the quantity that is actually needed.\n");
        sb.append(" - Total qty sum <= ").append(qty).append("; remaining quantity will be handled manually.\n");
        sb.append(" - If NO ticket matches, return []. Do not guess.\n");
        sb.append(" - Return JSON only, no markdown, no text before/after.\n");
        return sb.toString();
    }

    private static String nn(String s) { return s == null ? "?" : s; }

    private static List<Assignment> parseAssignments(String llm, int maxQty) {
        List<Assignment> out = new ArrayList<>();
        if (llm == null) return out;
        String clean = llm.trim();
        if (clean.startsWith("```")) {
            int nl = clean.indexOf('\n');
            if (nl > 0) clean = clean.substring(nl + 1);
            if (clean.endsWith("```")) clean = clean.substring(0, clean.length() - 3);
            clean = clean.trim();
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(clean);
            int running = 0;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                String id = o.optString("ticketId", "").trim();
                int q = o.optInt("qty", 0);
                String reason = o.optString("reason", "").trim();
                if (id.isEmpty() || q <= 0) continue;
                if (running + q > maxQty) q = maxQty - running;
                if (q <= 0) break;
                out.add(new Assignment(id, q, reason));
                running += q;
                if (running >= maxQty) break;
            }
        } catch (org.json.JSONException e) {
            Log.w(TAG, "JSON parse fail - raw=" + trim(clean, 300), e);
        }
        return out;
    }

    private static String extractOpenAiContent(String json) {
        if (json == null) return null;
        int c = json.indexOf("\"content\"");
        if (c < 0) return null;
        int colon = json.indexOf(':', c);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = q1 + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    default:  sb.append(n);
                }
            } else if (ch == '"') {
                return sb.toString();
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
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

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
