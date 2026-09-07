package com.example.stockit.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public final class JiraNotifier {

    public static final String CHANNEL_ID = "stockit_jira_assets";
    private static final String CHANNEL_NAME = "Jira Assets Creation";
    private static final String CHANNEL_DESC =
            "Progress and results of batch creation in Jira Assets.";

    private JiraNotifier() {}

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(CHANNEL_DESC);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    public static void showProgress(Context ctx, int notificationId, int done, int total) {
        ensureChannel(ctx);
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Creating Jira Assets...")
                .setContentText(done + "/" + total + " objects created")
                .setProgress(total, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId, n);
    }

    public static void showSuccess(Context ctx, int notificationId,
                                   int successCount, int total, String firstKey) {
        ensureChannel(ctx);
        String title = "Jira Assets: " + successCount + "/" + total + " created";
        String body;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (firstKey != null && !firstKey.isEmpty()) {
            body = "Open " + firstKey + " in Jira Assets";
                        String url = JiraUrlHelper.assetsAllListUrl();
            Intent openJira = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            openJira.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                       ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(ctx, notificationId, openJira, flags);
            builder.setContentIntent(pi);
        } else {
            body = "Completed";
        }
        builder.setContentText(body);

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId, builder.build());
    }

    public static void showPartial(Context ctx, int notificationId,
                                   int successCount, int failureCount,
                                   int total, String firstKey, String errorSample) {
        ensureChannel(ctx);
        String title = "Warning: Jira Assets partial: " + successCount + " OK, "
                + failureCount + " failed";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(errorSample != null ? errorSample : "See logcat")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        (firstKey != null ? "First created: " + firstKey + "\n" : "")
                                + "Error: " + (errorSample != null ? errorSample : "n/a")))
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        if (firstKey != null && !firstKey.isEmpty()) {
                        String url = JiraUrlHelper.assetsAllListUrl();
            Intent openJira = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            openJira.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                       ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(ctx, notificationId, openJira, flags);
            builder.setContentIntent(pi);
        }
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId, builder.build());
    }

    public static void showError(Context ctx, int notificationId, String errorSample) {
        ensureChannel(ctx);
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Jira Assets failure")
                .setContentText(errorSample != null ? errorSample : "See logcat")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        errorSample != null ? errorSample : "See logcat"))
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notificationId, n);
    }
}
