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

/**
 * StockIT PFE — Client minimal pour Jira Cloud REST API v3.
 * Auth : Basic (email + API token en Base64).
 *
 * Endpoint utilisé : POST /rest/api/3/issue
 * Doc : https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/
 */
public final class JiraClient {

    private static final String TAG = "JiraClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private JiraClient() {}

    public interface Callback {
        void onResult(boolean success, String issueKeyOrError);
    }

    /**
     * Crée une tâche (Task) sur le projet Jira indiqué.
     *
     * @param projectKey ex: "STOCK"
     * @param summary    titre de la tâche
     * @param description texte libre (converti en ADF plain paragraph)
     */
    public static void createTask(final String projectKey,
                                  final String summary,
                                  final String description,
                                  final Callback cb) {
        final String baseUrl = BuildConfig.JIRA_BASE_URL;
        final String email = BuildConfig.JIRA_USER_EMAIL;
        final String token = BuildConfig.JIRA_API_TOKEN;

        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(email) || TextUtils.isEmpty(token)) {
            Log.w(TAG, "Jira non configuré — création ignorée.");
            if (cb != null) cb.onResult(false, "jira_credentials_missing");
            return;
        }

        new Thread(() -> {
            try {
                String basic = "Basic " + Base64.encodeToString(
                        (email + ":" + token).getBytes("UTF-8"),
                        Base64.NO_WRAP);

                String issueType   = BuildConfig.JIRA_ISSUE_TYPE;
                String costCenter  = BuildConfig.JIRA_COST_CENTER;
                String component   = BuildConfig.JIRA_COMPONENT;
                if (issueType == null || issueType.isEmpty()) issueType = "Task";

                StringBuilder fields = new StringBuilder();
                fields.append("    \"project\": { \"key\": ").append(jsonQuote(projectKey)).append(" },\n");
                fields.append("    \"summary\": ").append(jsonQuote(summary)).append(",\n");
                fields.append("    \"issuetype\": { \"name\": ").append(jsonQuote(issueType)).append(" },\n");
                fields.append("    \"labels\": [\"StockIT-PFE\",\"AutoCreated\"],\n");
                // Component (ex : "ETX Tunis") - obligatoire pour la queue TUN-Unassigned
                if (component != null && !component.isEmpty()) {
                    fields.append("    \"components\": [{ \"name\": ").append(jsonQuote(component)).append(" }],\n");
                }
                // SD project requires Cost Center ID (customfield_11485)
                if (costCenter != null && !costCenter.isEmpty()) {
                    fields.append("    \"customfield_11485\": ").append(jsonQuote(costCenter)).append(",\n");
                }
                fields.append("    \"description\": {\n");
                fields.append("      \"type\": \"doc\", \"version\": 1,\n");
                fields.append("      \"content\": [{\n");
                fields.append("        \"type\": \"paragraph\",\n");
                fields.append("        \"content\": [{ \"type\": \"text\", \"text\": ")
                      .append(jsonQuote(description == null ? "" : description))
                      .append(" }]\n");
                fields.append("      }]\n");
                fields.append("    }\n");

                String body = "{\n  \"fields\": {\n" + fields + "  }\n}";

                Request req = new Request.Builder()
                        .url(baseUrl.replaceAll("/$", "") + "/rest/api/3/issue")
                        .header("Authorization", basic)
                        .header("Accept", "application/json")
                        .post(RequestBody.create(body, JSON))
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    String payload = resp.body() != null ? resp.body().string() : "";
                    Log.i(TAG, "Jira HTTP " + resp.code() + " -> " + trimForLog(payload));
                    if (resp.isSuccessful()) {
                        String key = extractField(payload, "key");
                        if (cb != null) cb.onResult(true, key != null ? key : "OK");
                    } else {
                        // Logue aussi le body envoyé pour aider au debug (400 = champs invalides).
                        Log.w(TAG, "Jira request body was: " + trimForLog(body));
                        if (cb != null) cb.onResult(false, "HTTP " + resp.code() + " " + trimForLog(payload));
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Jira error (silencée)", e);
                if (cb != null) cb.onResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "jira-client").start();
    }

    // --- utilitaires internes ---
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

    private static String extractField(String json, String field) {
        // Extraction naïve mais suffisante pour "key" à la racine
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String trimForLog(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
