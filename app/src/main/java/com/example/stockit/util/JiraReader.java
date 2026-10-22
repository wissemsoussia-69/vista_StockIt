package com.example.stockit.util;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.example.stockit.BuildConfig;
import com.example.stockit.model.JiraTicket;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * StockIT PFE — Lecture des tickets Jira Cloud REST v3.
 *
 * Endpoints utilisés (LECTURE SEULE) :
 *   GET /rest/api/3/search/jql?jql=...&fields=summary,status,priority,duedate,description,assignee,reporter
 *   GET /rest/api/3/issue/{key}?fields=...
 *
 * Auth : Basic (email + JIRA_API_TOKEN en Base64).
 * Le token existant fait à la fois read et write — on n'appelle QUE les endpoints GET ici.
 */
public final class JiraReader {

    private static final String TAG = "JiraReader";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private JiraReader() {}

    public interface SearchCallback { void onResult(List<JiraTicket> tickets, String error); }
    public interface GetCallback    { void onResult(JiraTicket ticket, String error); }

    /**
     * Charge tous les tickets ouverts du projet configuré.
     * JQL : project = X AND statusCategory != Done ORDER BY priority DESC, duedate ASC
     */
    public static void loadOpenTickets(final int maxResults, final SearchCallback cb) {
        String jql = "project = " + BuildConfig.JIRA_PROJECT_KEY
                + " AND statusCategory != Done ORDER BY priority DESC, duedate ASC";
        searchByJql(jql, maxResults, cb);
    }

    public static void searchByJql(final String jql, final int maxResults, final SearchCallback cb) {
        new Thread(() -> {
            try {
                String url = BuildConfig.JIRA_BASE_URL.replaceAll("/$", "")
                        + "/rest/api/3/search/jql"
                        + "?jql=" + java.net.URLEncoder.encode(jql, "UTF-8")
                        + "&fields=summary,status,priority,duedate,description,assignee,reporter,labels"
                        + "&maxResults=" + Math.max(1, maxResults);

                Request req = new Request.Builder()
                        .url(url)
                        .header("Authorization", basicAuthHeader())
                        .header("Accept", "application/json")
                        .get()
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    String payload = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        Log.w(TAG, "search HTTP " + resp.code() + " -> " + trim(payload, 300));
                        cb.onResult(null, "HTTP " + resp.code() + " " + trim(payload, 150));
                        return;
                    }
                    List<JiraTicket> list = parseSearchResponse(payload);
                    Log.i(TAG, "search OK — " + list.size() + " tickets");
                    cb.onResult(list, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "search error", t);
                cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-reader").start();
    }

    /** Récupère un ticket unique par sa clé (pour valider une saisie manuelle). */
    public static void getIssue(final String key, final GetCallback cb) {
        new Thread(() -> {
            try {
                String url = BuildConfig.JIRA_BASE_URL.replaceAll("/$", "")
                        + "/rest/api/3/issue/" + key.trim()
                        + "?fields=summary,status,priority,duedate,description,assignee,reporter,labels";

                Request req = new Request.Builder()
                        .url(url)
                        .header("Authorization", basicAuthHeader())
                        .header("Accept", "application/json")
                        .get()
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    String payload = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        Log.w(TAG, "getIssue HTTP " + resp.code() + " -> " + trim(payload, 300));
                        cb.onResult(null, "HTTP " + resp.code());
                        return;
                    }
                    JiraTicket t = parseIssue(new org.json.JSONObject(payload));
                    cb.onResult(t, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "getIssue error", t);
                cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-reader-single").start();
    }

    // ---------- Parsing ----------
    private static List<JiraTicket> parseSearchResponse(String payload) throws org.json.JSONException {
        List<JiraTicket> out = new ArrayList<>();
        org.json.JSONObject root = new org.json.JSONObject(payload);
        org.json.JSONArray issues = root.optJSONArray("issues");
        if (issues == null) return out;
        for (int i = 0; i < issues.length(); i++) {
            org.json.JSONObject o = issues.getJSONObject(i);
            out.add(parseIssue(o));
        }
        return out;
    }

    private static JiraTicket parseIssue(org.json.JSONObject o) {
        JiraTicket t = new JiraTicket();
        t.key = o.optString("key", null);
        org.json.JSONObject f = o.optJSONObject("fields");
        if (f == null) return t;
        t.summary  = f.optString("summary", null);
        t.dueDate  = optStrOrNull(f, "duedate");

        org.json.JSONObject st = f.optJSONObject("status");
        if (st != null) t.status = st.optString("name", null);

        org.json.JSONObject pr = f.optJSONObject("priority");
        if (pr != null) t.priority = pr.optString("name", null);

        org.json.JSONObject asg = f.optJSONObject("assignee");
        if (asg != null) t.assignee = asg.optString("displayName", null);

        org.json.JSONObject rep = f.optJSONObject("reporter");
        if (rep != null) t.reporter = rep.optString("displayName", null);

        // Description : ADF -> texte plat
        Object descRaw = f.opt("description");
        if (descRaw instanceof org.json.JSONObject) {
            t.description = flattenAdf((org.json.JSONObject) descRaw);
        } else if (descRaw instanceof String) {
            t.description = (String) descRaw;
        }
        return t;
    }

    /** Aplatit un document ADF (Atlassian Document Format) en texte lisible. */
    static String flattenAdf(org.json.JSONObject node) {
        StringBuilder sb = new StringBuilder();
        flattenAdfInto(node, sb);
        return sb.toString().trim();
    }

    private static void flattenAdfInto(Object node, StringBuilder sb) {
        if (node == null) return;
        if (node instanceof org.json.JSONObject) {
            org.json.JSONObject obj = (org.json.JSONObject) node;
            String type = obj.optString("type", "");
            if ("text".equals(type)) {
                sb.append(obj.optString("text", "")).append(' ');
                return;
            }
            if ("hardBreak".equals(type) || "paragraph".equals(type)) {
                // rien de plus, on descend puis on ajoutera newline
            }
            org.json.JSONArray content = obj.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    flattenAdfInto(content.opt(i), sb);
                }
            }
            if ("paragraph".equals(type) || "heading".equals(type) || "listItem".equals(type)) {
                sb.append('\n');
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) node;
            for (int i = 0; i < arr.length(); i++) flattenAdfInto(arr.opt(i), sb);
        }
    }

    // ---------- helpers ----------
    private static String basicAuthHeader() throws UnsupportedEncodingException {
        String creds = BuildConfig.JIRA_USER_EMAIL + ":" + BuildConfig.JIRA_API_TOKEN;
        return "Basic " + Base64.encodeToString(creds.getBytes("UTF-8"), Base64.NO_WRAP);
    }

    private static String optStrOrNull(org.json.JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        String v = o.optString(k, "").trim();
        return v.isEmpty() ? null : v;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
