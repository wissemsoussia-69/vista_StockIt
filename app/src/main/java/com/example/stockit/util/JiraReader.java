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

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class JiraReader {

    private static final String TAG = "JiraReader";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private JiraReader() {}

    public interface SearchCallback { void onResult(List<JiraTicket> tickets, String error); }
    public interface GetCallback    { void onResult(JiraTicket ticket, String error); }

    public static void loadOpenTickets(final int maxResults, final SearchCallback cb) {
        String jql = "project = " + BuildConfig.JIRA_PROJECT_KEY
                + " AND statusCategory != Done ORDER BY priority DESC, duedate ASC";
        searchByJql(jql, maxResults, cb);
    }

    public static void searchByJql(final String jql, final int maxResults, final SearchCallback cb) {
        new Thread(() -> {
            try {
                String fields = "summary,status,priority,duedate,description,assignee,reporter,labels";

                String postUrl = JiraUrlHelper.apiUrl("/rest/api/3/search/jql");
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("jql", jql);
                body.put("maxResults", Math.max(1, maxResults));
                body.put("fields", new org.json.JSONArray(fields.split(",")));

                Request postReq = new Request.Builder()
                        .url(postUrl)
                        .header("Authorization", basicAuthHeader())
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                try (Response postResp = CLIENT.newCall(postReq).execute()) {
                    String postPayload = postResp.body() != null ? postResp.body().string() : "";
                    if (postResp.isSuccessful()) {
                        List<JiraTicket> list = parseSearchResponse(postPayload);
                        Log.i(TAG, "search POST /search/jql OK - " + list.size() + " tickets");
                        cb.onResult(list, null);
                        return;
                    }

                    Log.w(TAG, "search POST /search/jql HTTP " + postResp.code() + " -> " + trim(postPayload, 220));
                    String getUrl = JiraUrlHelper.apiUrl("/rest/api/3/search/jql")
                            + "?jql=" + java.net.URLEncoder.encode(jql, "UTF-8")
                            + "&fields=" + java.net.URLEncoder.encode(fields, "UTF-8")
                            + "&maxResults=" + Math.max(1, maxResults);

                    Request getReq = new Request.Builder()
                            .url(getUrl)
                            .header("Authorization", basicAuthHeader())
                            .header("Accept", "application/json")
                            .get()
                            .build();

                    try (Response getResp = CLIENT.newCall(getReq).execute()) {
                        String getPayload = getResp.body() != null ? getResp.body().string() : "";
                        if (!getResp.isSuccessful()) {
                            Log.w(TAG, "search GET /search/jql HTTP " + getResp.code() + " -> " + trim(getPayload, 300));
                            cb.onResult(null, "HTTP " + getResp.code() + " " + trim(getPayload, 150));
                            return;
                        }
                        List<JiraTicket> list = parseSearchResponse(getPayload);
                        Log.i(TAG, "search GET /search/jql OK - " + list.size() + " tickets");
                        cb.onResult(list, null);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "search error", t);
                cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-reader").start();
    }

    public static void getIssue(final String key, final GetCallback cb) {
        new Thread(() -> {
            try {
                String url = JiraUrlHelper.apiUrl("/rest/api/3/issue")
                    + "/" + key.trim()
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

        Object descRaw = f.opt("description");
        if (descRaw instanceof org.json.JSONObject) {
            t.description = flattenAdf((org.json.JSONObject) descRaw);
        } else if (descRaw instanceof String) {
            t.description = (String) descRaw;
        }
        return t;
    }

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
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
