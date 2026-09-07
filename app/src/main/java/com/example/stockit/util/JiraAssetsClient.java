package com.example.stockit.util;

import android.util.Base64;
import android.util.Log;

import com.example.stockit.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class JiraAssetsClient {

    private static final String TAG = "JiraAssetsClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ASSETS_API_BASE = "https://api.atlassian.com/jsm/assets/workspace";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private static volatile String CACHED_WORKSPACE_ID;
    private static final Map<Integer, Map<String, AttributeInfo>> ATTR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> REF_OBJECT_CACHE = new ConcurrentHashMap<>();

    private JiraAssetsClient() {}


    public interface BatchCallback {
        void onDone(int successCount, int failureCount, List<String> createdKeys, String errorSample);
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    public static final class AssetRecord {
        public final String id;
        public final String objectKey;
        public final String label;
        public final String serialNumber;
        public final String poNumber;
        public final String assetStatus;

        AssetRecord(String id, String objectKey, String label, String serialNumber, String poNumber, String assetStatus) {
            this.id = id;
            this.objectKey = objectKey;
            this.label = label;
            this.serialNumber = serialNumber;
            this.poNumber = poNumber;
            this.assetStatus = assetStatus;
        }
    }

    public interface AssetsSearchCallback {
        void onResult(List<AssetRecord> assets, String error);
    }

    public interface AssetsUpdateCallback {
        void onDone(int updatedCount, int failedCount, String errorSample);
    }

    public static void createAssetsBatch(final int objectTypeId,
                                         final List<Map<String, String>> assets,
                                         final Progress progress,
                                         final BatchCallback cb) {
        if (assets == null || assets.isEmpty()) {
            if (cb != null) cb.onDone(0, 0, new ArrayList<>(), "empty_batch");
            return;
        }

        new Thread(() -> {
            String basic = basicAuth();
            if (basic == null) {
                if (cb != null) cb.onDone(0, assets.size(), new ArrayList<>(), "jira_credentials_missing");
                return;
            }

            try {
                String wsId = fetchWorkspaceId(basic);
                if (wsId == null) {
                    if (cb != null) cb.onDone(0, assets.size(), new ArrayList<>(), "workspace_id_not_found");
                    return;
                }

                Map<String, AttributeInfo> schema = fetchAttributeSchema(basic, wsId, objectTypeId);
                if (schema == null || schema.isEmpty()) {
                    if (cb != null) cb.onDone(0, assets.size(), new ArrayList<>(), "no_attributes_for_type_" + objectTypeId);
                    return;
                }

                int ok = 0, ko = 0;
                List<String> keys = new ArrayList<>();
                String firstError = null;

                for (int i = 0; i < assets.size(); i++) {
                    Map<String, String> attrs = assets.get(i);
                    try {
                        JSONObject payload = buildCreatePayload(basic, wsId, objectTypeId, schema, attrs);
                        String key = postCreateObject(basic, wsId, payload);
                        if (key != null) { ok++; keys.add(key); }
                        else { ko++; if (firstError == null) firstError = "create_returned_no_key"; }
                    } catch (Throwable t) {
                        ko++;
                        String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                        if (firstError == null) firstError = msg;
                        Log.w(TAG, "Asset " + (i + 1) + "/" + assets.size() + " failed: " + msg);
                    }
                    if (progress != null) progress.onProgress(i + 1, assets.size());
                }

                if (cb != null) cb.onDone(ok, ko, keys, firstError);
            } catch (Throwable t) {
                Log.e(TAG, "batch fatal", t);
                if (cb != null) cb.onDone(0, assets.size(), new ArrayList<>(), t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-assets-batch").start();
    }

    public static void searchAssetsByAql(final String aql,
                                         final int maxResults,
                                         final AssetsSearchCallback cb) {
        new Thread(() -> {
            try {
                String basic = basicAuth();
                if (basic == null) {
                    if (cb != null) cb.onResult(null, "jira_credentials_missing");
                    return;
                }

                String wsId = fetchWorkspaceId(basic);
                if (wsId == null || wsId.isEmpty()) {
                    if (cb != null) cb.onResult(null, "workspace_id_not_found");
                    return;
                }

                JSONObject body = new JSONObject();
                body.put("qlQuery", aql == null ? "" : aql);
                body.put("startAt", 0);
                body.put("maxResults", Math.max(1, maxResults));
                body.put("includeAttributes", true);

                String url = ASSETS_API_BASE + "/" + wsId + "/v1/object/aql";
                Request req = new Request.Builder()
                        .url(url)
                        .header("Authorization", basic)
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                try (Response resp = CLIENT.newCall(req).execute()) {
                    String payload = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        Log.w(TAG, "searchAssetsByAql HTTP " + resp.code() + " -> " + preview(payload, 300));
                        if (cb != null) cb.onResult(null, "HTTP " + resp.code());
                        return;
                    }

                    JSONObject root = new JSONObject(payload);
                    JSONArray values = root.optJSONArray("values");
                    List<AssetRecord> out = new ArrayList<>();
                    if (values != null) {
                        for (int i = 0; i < values.length(); i++) {
                            JSONObject o = values.optJSONObject(i);
                            if (o == null) continue;
                            AssetRecord rec = parseAssetRecord(o);
                            if (rec != null) out.add(rec);
                        }
                    }
                    if (cb != null) cb.onResult(out, null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "searchAssetsByAql error", t);
                if (cb != null) cb.onResult(null, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-assets-search").start();
    }

    public static void updateAssetStatusByIds(final int objectTypeId,
                                              final List<String> objectIds,
                                              final String newStatus,
                                              final AssetsUpdateCallback cb) {
        if (objectIds == null || objectIds.isEmpty()) {
            if (cb != null) cb.onDone(0, 0, "no_object_ids");
            return;
        }

        new Thread(() -> {
            String basic = basicAuth();
            if (basic == null) {
                if (cb != null) cb.onDone(0, objectIds.size(), "jira_credentials_missing");
                return;
            }

            try {
                String wsId = fetchWorkspaceId(basic);
                if (wsId == null || wsId.isEmpty()) {
                    if (cb != null) cb.onDone(0, objectIds.size(), "workspace_id_not_found");
                    return;
                }

                Map<String, AttributeInfo> schema = fetchAttributeSchema(basic, wsId, objectTypeId);
                if (schema == null || schema.isEmpty()) {
                    if (cb != null) cb.onDone(0, objectIds.size(), "no_attributes_for_type_" + objectTypeId);
                    return;
                }

                AttributeInfo statusAttr = schema.get("asset status");
                if (statusAttr == null) {
                    if (cb != null) cb.onDone(0, objectIds.size(), "asset_status_attribute_not_found");
                    return;
                }

                int ok = 0;
                int ko = 0;
                String firstError = null;

                for (String objectId : objectIds) {
                    if (objectId == null || objectId.trim().isEmpty()) {
                        ko++;
                        if (firstError == null) firstError = "empty_object_id";
                        continue;
                    }

                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("objectTypeId", objectTypeId);

                        JSONArray attrs = new JSONArray();
                        JSONObject status = new JSONObject();
                        status.put("objectTypeAttributeId", statusAttr.id);
                        JSONArray vals = new JSONArray();
                        JSONObject v = new JSONObject();
                        v.put("value", newStatus == null ? "Out of stock" : newStatus);
                        vals.put(v);
                        status.put("objectAttributeValues", vals);
                        attrs.put(status);
                        payload.put("attributes", attrs);

                        boolean done = putUpdateObject(basic, wsId, objectId.trim(), payload);
                        if (done) {
                            ok++;
                        } else {
                            ko++;
                            if (firstError == null) firstError = "update_failed_" + objectId;
                        }
                    } catch (Throwable t) {
                        ko++;
                        String e = t.getClass().getSimpleName() + ": " + t.getMessage();
                        if (firstError == null) firstError = e;
                        Log.w(TAG, "update status failed for objectId=" + objectId + " -> " + e);
                    }
                }

                if (cb != null) cb.onDone(ok, ko, firstError);
            } catch (Throwable t) {
                Log.e(TAG, "updateAssetStatusByIds fatal", t);
                if (cb != null) cb.onDone(0, objectIds.size(), t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-assets-update").start();
    }

    public static void updateAssetStatusAndAssigneeByIds(final int objectTypeId,
                                                         final List<String> objectIds,
                                                         final String newStatus,
                                                         final String assignedUser,
                                                         final String assignedEmail,
                                                         final AssetsUpdateCallback cb) {
        if (objectIds == null || objectIds.isEmpty()) {
            if (cb != null) cb.onDone(0, 0, "no_object_ids");
            return;
        }

        new Thread(() -> {
            String basic = basicAuth();
            if (basic == null) {
                if (cb != null) cb.onDone(0, objectIds.size(), "jira_credentials_missing");
                return;
            }

            try {
                String wsId = fetchWorkspaceId(basic);
                if (wsId == null || wsId.isEmpty()) {
                    if (cb != null) cb.onDone(0, objectIds.size(), "workspace_id_not_found");
                    return;
                }

                Map<String, AttributeInfo> schema = fetchAttributeSchema(basic, wsId, objectTypeId);
                if (schema == null || schema.isEmpty()) {
                    if (cb != null) cb.onDone(0, objectIds.size(), "no_attributes_for_type_" + objectTypeId);
                    return;
                }

                AttributeInfo statusAttr = schema.get("asset status");
                AttributeInfo assignedUserAttr = schema.get("assigned user");
                AttributeInfo emailAttr = schema.get("email address");

                if (statusAttr == null) {
                    if (cb != null) cb.onDone(0, objectIds.size(), "asset_status_attribute_not_found");
                    return;
                }

                String user = assignedUser == null ? "" : assignedUser.trim();
                String mail = assignedEmail == null ? "" : assignedEmail.trim();

                int ok = 0;
                int ko = 0;
                String firstError = null;

                for (String objectId : objectIds) {
                    if (objectId == null || objectId.trim().isEmpty()) {
                        ko++;
                        if (firstError == null) firstError = "empty_object_id";
                        continue;
                    }

                    try {
                        JSONObject payload = new JSONObject();
                        payload.put("objectTypeId", objectTypeId);

                        JSONArray attrs = new JSONArray();

                        JSONObject status = new JSONObject();
                        status.put("objectTypeAttributeId", statusAttr.id);
                        JSONArray statusVals = new JSONArray();
                        JSONObject statusValue = new JSONObject();
                        statusValue.put("value", newStatus == null ? "Inactive" : newStatus);
                        statusVals.put(statusValue);
                        status.put("objectAttributeValues", statusVals);
                        attrs.put(status);

                        if (!user.isEmpty() && assignedUserAttr != null) {
                            JSONObject assignee = new JSONObject();
                            assignee.put("objectTypeAttributeId", assignedUserAttr.id);
                            JSONArray assigneeVals = new JSONArray();
                            JSONObject assigneeValue = new JSONObject();
                            assigneeValue.put("value", user);
                            assigneeVals.put(assigneeValue);
                            assignee.put("objectAttributeValues", assigneeVals);
                            attrs.put(assignee);
                        }

                        if (!mail.isEmpty() && emailAttr != null) {
                            JSONObject email = new JSONObject();
                            email.put("objectTypeAttributeId", emailAttr.id);
                            JSONArray emailVals = new JSONArray();
                            JSONObject emailValue = new JSONObject();
                            emailValue.put("value", mail);
                            emailVals.put(emailValue);
                            email.put("objectAttributeValues", emailVals);
                            attrs.put(email);
                        }

                        payload.put("attributes", attrs);

                        boolean done = putUpdateObject(basic, wsId, objectId.trim(), payload);
                        if (done) {
                            ok++;
                        } else {
                            ko++;
                            if (firstError == null) firstError = "update_failed_" + objectId;
                        }
                    } catch (Throwable t) {
                        ko++;
                        String e = t.getClass().getSimpleName() + ": " + t.getMessage();
                        if (firstError == null) firstError = e;
                        Log.w(TAG, "update status/user failed for objectId=" + objectId + " -> " + e);
                    }
                }

                if (cb != null) cb.onDone(ok, ko, firstError);
            } catch (Throwable t) {
                Log.e(TAG, "updateAssetStatusAndAssigneeByIds fatal", t);
                if (cb != null) cb.onDone(0, objectIds.size(), t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, "jira-assets-update-assignee").start();
    }


    private static String fetchWorkspaceId(String basic) throws IOException {
        String cached = CACHED_WORKSPACE_ID;
        if (cached != null) return cached;

        String baseUrl = BuildConfig.JIRA_BASE_URL;
        if (baseUrl == null || baseUrl.isEmpty()) return null;

        String url = baseUrl.replaceAll("/$", "") + "/rest/servicedeskapi/assets/workspace";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", basic)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "workspace HTTP " + resp.code() + " -> " + preview(body, 200));
                return null;
            }
            JSONObject obj = new JSONObject(body);
            JSONArray values = obj.optJSONArray("values");
            if (values == null || values.length() == 0) return null;
            String ws = values.getJSONObject(0).optString("workspaceId", null);
            if (ws != null && !ws.isEmpty()) {
                CACHED_WORKSPACE_ID = ws;
                Log.i(TAG, "workspaceId discovered: " + ws);
                return ws;
            }
            return null;
        } catch (JSONException e) {
            Log.e(TAG, "workspace json parse", e);
            return null;
        }
    }


    private static final class AttributeInfo {
        final String id;
        final boolean isReference;
        final Integer referenceTypeId; // objectTypeId de la target si reference
        AttributeInfo(String id, boolean isRef, Integer refTypeId) {
            this.id = id; this.isReference = isRef; this.referenceTypeId = refTypeId;
        }
    }

    private static Map<String, AttributeInfo> fetchAttributeSchema(String basic, String wsId, int objectTypeId) throws IOException {
        Map<String, AttributeInfo> cached = ATTR_CACHE.get(objectTypeId);
        if (cached != null) return cached;

        String url = ASSETS_API_BASE + "/" + wsId + "/v1/objecttype/" + objectTypeId + "/attributes";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", basic)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "schema HTTP " + resp.code() + " -> " + preview(body, 200));
                return null;
            }
            JSONArray arr = new JSONArray(body);
            Map<String, AttributeInfo> map = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String name = a.optString("name", "").trim();
                String id   = a.optString("id", "");
                if (name.isEmpty() || id.isEmpty()) continue;
                int type = a.optInt("type", 0);
                boolean isRef = (type == 1);
                Integer refTypeId = null;
                if (isRef && a.has("referenceObjectTypeId") && !a.isNull("referenceObjectTypeId")) {
                    refTypeId = a.optInt("referenceObjectTypeId");
                    if (refTypeId <= 0) refTypeId = null;
                }
                map.put(name.toLowerCase(Locale.ROOT), new AttributeInfo(id, isRef, refTypeId));
            }
            ATTR_CACHE.put(objectTypeId, map);
            Log.i(TAG, "attributes discovered for type " + objectTypeId + ": " + map.size() + " fields");
            return map;
        } catch (JSONException e) {
            Log.e(TAG, "schema json parse", e);
            return null;
        }
    }


    private static JSONObject buildCreatePayload(String basic, String wsId, int objectTypeId,
                                                 Map<String, AttributeInfo> schema,
                                                 Map<String, String> attrs) throws JSONException, IOException {
        JSONArray attrArr = new JSONArray();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (name == null || value == null || value.isEmpty()) continue;

            AttributeInfo info = schema.get(name.toLowerCase(Locale.ROOT));
            if (info == null) {
                Log.w(TAG, "attribute not in schema (skipped): " + name);
                continue;
            }

            String sentValue = value;
            if (info.isReference) {
                String refObjectId = resolveReferenceObjectId(basic, wsId, info.referenceTypeId, value);
                if (refObjectId == null) {
                    Log.w(TAG, "reference lookup failed for '" + name + "' = '" + value + "' (skipped)");
                    continue;
                }
                sentValue = refObjectId;
            }

            JSONObject attr = new JSONObject();
            attr.put("objectTypeAttributeId", info.id);
            JSONArray vals = new JSONArray();
            JSONObject v = new JSONObject();
            v.put("value", sentValue);
            vals.put(v);
            attr.put("objectAttributeValues", vals);
            attrArr.put(attr);
        }

        JSONObject payload = new JSONObject();
        payload.put("objectTypeId", objectTypeId);
        payload.put("attributes", attrArr);
        return payload;
    }

    private static String postCreateObject(String basic, String wsId, JSONObject payload) throws IOException {
        String url = ASSETS_API_BASE + "/" + wsId + "/v1/object/create";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", basic)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "create HTTP " + resp.code() + " -> " + preview(body, 400));
                Log.w(TAG, "create payload was: " + preview(payload.toString(), 400));
                return null;
            }
            try {
                JSONObject obj = new JSONObject(body);
                String key = obj.optString("objectKey", null);
                if (key == null || key.isEmpty()) key = obj.optString("id", null);
                Log.i(TAG, "asset created: " + key);
                return key;
            } catch (JSONException e) {
                Log.w(TAG, "create response parse", e);
                return null;
            }
        }
    }


    private static String resolveReferenceObjectId(String basic, String wsId, Integer refTypeId, String name) throws IOException {
        String cacheKey = (refTypeId == null ? "any" : refTypeId.toString()) + ":" + name.toLowerCase(Locale.ROOT);
        String cached = REF_OBJECT_CACHE.get(cacheKey);
        if (cached != null) return cached;

        String aql;
        if (refTypeId != null) {
            aql = "objectTypeId = " + refTypeId + " AND Name = \"" + name.replace("\"", "\\\"") + "\"";
        } else {
            aql = "Name = \"" + name.replace("\"", "\\\"") + "\"";
        }

        JSONObject body = new JSONObject();
        try { body.put("qlQuery", aql); } catch (JSONException ignored) {}

        String url = ASSETS_API_BASE + "/" + wsId + "/v1/object/aql";
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", basic)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            String payload = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "aql HTTP " + resp.code() + " -> " + preview(payload, 200));
                return null;
            }
            JSONObject obj = new JSONObject(payload);
            JSONArray entries = obj.optJSONArray("values");
            if (entries == null || entries.length() == 0) {
                Log.w(TAG, "aql no match for: " + aql);
                return null;
            }
            String id = entries.getJSONObject(0).optString("id", null);
            if (id != null && !id.isEmpty()) {
                REF_OBJECT_CACHE.put(cacheKey, id);
                return id;
            }
            return null;
        } catch (JSONException e) {
            Log.e(TAG, "aql json parse", e);
            return null;
        }
    }

    private static boolean putUpdateObject(String basic, String wsId, String objectId, JSONObject payload) throws IOException {
        String url = ASSETS_API_BASE + "/" + wsId + "/v1/object/" + objectId;
        Request req = new Request.Builder()
                .url(url)
                .header("Authorization", basic)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .put(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                Log.w(TAG, "update HTTP " + resp.code() + " objectId=" + objectId + " -> " + preview(body, 300));
                return false;
            }
            return true;
        }
    }

    private static AssetRecord parseAssetRecord(JSONObject o) {
        String id = o.optString("id", "");
        String objectKey = o.optString("objectKey", "");
        String label = o.optString("label", "");
        if (label.isEmpty()) label = o.optString("name", "");

        String serial = "";
        String po = "";
        String status = "";

        JSONArray attrs = o.optJSONArray("attributes");
        if (attrs == null) attrs = o.optJSONArray("objectAttributeBeans");

        if (attrs != null) {
            for (int i = 0; i < attrs.length(); i++) {
                JSONObject a = attrs.optJSONObject(i);
                if (a == null) continue;

                String attrName = "";
                JSONObject typeAttr = a.optJSONObject("objectTypeAttribute");
                if (typeAttr != null) attrName = typeAttr.optString("name", "");
                if (attrName.isEmpty()) attrName = a.optString("name", "");

                String value = extractFirstAttributeValue(a);
                if (value.isEmpty()) continue;

                if ("Serial Number".equalsIgnoreCase(attrName)) serial = value;
                if ("PO Number".equalsIgnoreCase(attrName)) po = value;
                if ("Asset Status".equalsIgnoreCase(attrName)) status = value;
                if (label.isEmpty() && "Name".equalsIgnoreCase(attrName)) label = value;
            }
        }

        return new AssetRecord(id, objectKey, label, serial, po, status);
    }

    private static String extractFirstAttributeValue(JSONObject attr) {
        JSONArray values = attr.optJSONArray("objectAttributeValues");
        if (values == null || values.length() == 0) return "";

        JSONObject v = values.optJSONObject(0);
        if (v == null) return "";

        String s = v.optString("displayValue", "").trim();
        if (!s.isEmpty()) return s;
        s = v.optString("searchValue", "").trim();
        if (!s.isEmpty()) return s;
        s = v.optString("value", "").trim();
        if (!s.isEmpty()) return s;
        return "";
    }


    private static String basicAuth() {
        String email = BuildConfig.JIRA_USER_EMAIL;
        String token = BuildConfig.JIRA_API_TOKEN;
        if (email == null || email.isEmpty() || token == null || token.isEmpty()) return null;
        try {
            return "Basic " + Base64.encodeToString((email + ":" + token).getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String preview(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
