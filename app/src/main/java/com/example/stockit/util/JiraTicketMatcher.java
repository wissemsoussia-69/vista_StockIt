package com.example.stockit.util;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.stockit.BuildConfig;
import com.example.stockit.model.JiraTicket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class JiraTicketMatcher {

    private static final String TAG = "JiraTicketMatcher";
    private static final int NOTIF_ID_BASE = 200_000; // separate from JiraNotifier

    private JiraTicketMatcher() {}

    public static void findAndNotify(final Context ctx, final String assetName) {
        if (ctx == null || assetName == null || assetName.trim().isEmpty()) return;

        final List<String> keywords = keywordsFor(assetName);
        if (keywords.isEmpty()) {
            Log.d(TAG, "no relevant keyword for '" + assetName + "', skip");
            return;
        }

        StringBuilder jql = new StringBuilder("project = ").append(BuildConfig.JIRA_PROJECT_KEY)
                .append(" AND statusCategory != Done AND (");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) jql.append(" OR ");
            jql.append("summary ~ \"").append(keywords.get(i)).append("\"");
        }
        jql.append(") ORDER BY priority DESC, created ASC");

        Log.i(TAG, "searching tickets for '" + assetName + "' -> JQL: " + jql);
        JiraReader.searchByJql(jql.toString(), 10, (tickets, error) -> {
            if (error != null) {
                Log.w(TAG, "search failed: " + error);
                return;
            }
            if (tickets == null || tickets.isEmpty()) {
                Log.i(TAG, "no open ticket matches '" + assetName + "'");
                return;
            }
            postMatchNotification(ctx.getApplicationContext(), assetName, tickets);
        });
    }


    private static List<String> keywordsFor(String assetName) {
        String n = assetName.toLowerCase(Locale.ROOT).trim();
        List<String> kw = new ArrayList<>();

        if (contains(n, "headset", "headphone", "audio", "stereo",
                     "epos", "sennheiser", "jabra")) {
            kw.add("headset");
            kw.add("headset");
        }
        else if (contains(n, "mouse")) {
            kw.add("mouse");
            kw.add("mouse");
        }
        else if (contains(n, "keyboard")) {
            kw.add("keyboard");
            kw.add("keyboard");
        }
        else if (contains(n, "screen", "monitor", "display")) {
            kw.add("screen");
            kw.add("monitor");
        }
        else if (contains(n, "computer", "laptop", "desktop", "pc", "notebook")) {
            kw.add("computer");
            kw.add("laptop");
        }
        else if (contains(n, "dock", "docking", "adapter",
                          "hdmi", "usb-c", "thunderbolt")) {
            kw.add("dock");
            kw.add("adapter");
        }
        else {
            String[] parts = n.split("\\s+");
            if (parts.length > 0 && parts[0].length() >= 3) {
                kw.add(parts[0]);
            }
        }
        return kw;
    }

    private static boolean contains(String haystack, String... needles) {
        for (String needle : needles) if (haystack.contains(needle)) return true;
        return false;
    }


    private static void postMatchNotification(Context appCtx, String assetName, List<JiraTicket> tickets) {
        JiraNotifier.ensureChannel(appCtx);

        JiraTicket first = tickets.get(0);
        String firstKey = first.key;
        String summary = firstKey + "  |  " + safeSummary(first.summary);
        int extraCount = tickets.size() - 1;
        if (extraCount > 0) summary += " (+ " + extraCount + " more)";

        String bigText = buildBigText(tickets);

        String url = RecentAssetsStore.jiraUrl(firstKey);
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                   ? PendingIntent.FLAG_IMMUTABLE : 0);
        int notifId = NOTIF_ID_BASE + (int) (System.currentTimeMillis() & 0xffff);
        PendingIntent pi = PendingIntent.getActivity(appCtx, notifId, open, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(appCtx, JiraNotifier.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(tickets.size() + " ticket(s) waiting for " + assetName)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager nm = (NotificationManager)
                appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifId, b.build());
    }

    private static String buildBigText(List<JiraTicket> tickets) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(tickets.size(), 5);
        for (int i = 0; i < shown; i++) {
            JiraTicket t = tickets.get(i);
                sb.append("- ").append(t.key)
                    .append(" - ").append(safeSummary(t.summary));
            if (t.priority != null && !t.priority.isEmpty()) {
                sb.append(" [").append(t.priority).append("]");
            }
            if (i < shown - 1) sb.append("\n");
        }
        if (tickets.size() > shown) {
            sb.append("\n... + ").append(tickets.size() - shown).append(" more");
        }
        return sb.toString();
    }

    private static String safeSummary(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
