package com.example.stockit.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RecentAssetsStore {

    private static final String PREFS = "stockit_recent_assets";
    private static final String KEY_JSON = "items_json";
    private static final int MAX_ITEMS = 5;

    private RecentAssetsStore() {}

    public static final class Entry {
        public final String name;
        public final String jiraKey;
        public final int quantity;
        public final long timestampMs;

        public Entry(String name, String jiraKey, int quantity, long timestampMs) {
            this.name = name;
            this.jiraKey = jiraKey;
            this.quantity = quantity;
            this.timestampMs = timestampMs;
        }
    }

    public static void add(Context ctx, String name, String jiraKey, int quantity) {
        if (jiraKey == null || jiraKey.isEmpty()) return;
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<Entry> current = load(ctx);
        List<Entry> filtered = new ArrayList<>(current.size());
        for (Entry e : current) if (!jiraKey.equals(e.jiraKey)) filtered.add(e);
        filtered.add(0, new Entry(name, jiraKey, quantity, System.currentTimeMillis()));
        while (filtered.size() > MAX_ITEMS) filtered.remove(filtered.size() - 1);
        prefs.edit().putString(KEY_JSON, serialize(filtered)).apply();
    }

    public static List<Entry> load(Context ctx) {
        SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_JSON, null);
        if (raw == null || raw.isEmpty()) return new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            List<Entry> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(
                        o.optString("name", ""),
                        o.optString("jiraKey", ""),
                        o.optInt("quantity", 0),
                        o.optLong("ts", 0L)));
            }
            return out;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    public static void clear(Context ctx) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_JSON).apply();
    }

    private static String serialize(List<Entry> items) {
        JSONArray arr = new JSONArray();
        for (Entry e : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e.name != null ? e.name : "");
                o.put("jiraKey", e.jiraKey != null ? e.jiraKey : "");
                o.put("quantity", e.quantity);
                o.put("ts", e.timestampMs);
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    public static String humanizeDelta(long ts) {
        long now = System.currentTimeMillis();
        long diffMs = Math.max(0L, now - ts);
        long sec = diffMs / 1000L;
        if (sec < 60) return "just now";
        long min = sec / 60L;
        if (min < 60) return min + "m ago";
        long hr = min / 60L;
        if (hr < 24) return hr + "h ago";
        long day = hr / 24L;
        if (day == 1) return "yesterday";
        return day + "d ago";
    }

    public static String jiraUrl(String jiraKey) {
        return JiraUrlHelper.browseUrl(jiraKey);
    }

    public static String assetsListUrlForName(String assetName) {
        return JiraUrlHelper.assetsListUrlForName(assetName);
    }

    public static String assetsAllListUrl() {
        return JiraUrlHelper.assetsAllListUrl();
    }
}
