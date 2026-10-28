package com.example.stockit.controller;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.example.stockit.R;

public class NotificationHelper {
    private static final String CHANNEL_ID = "stock_alerts";
    private static final String CHANNEL_NAME = "Alertes de Stock";
    private static final String CHANNEL_DESC = "Notifications pour les seuils bas et ruptures de stock";

    public static void showNotification(Context context, String title, String message, int notificationId) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, com.example.stockit.util.AppLanguage.L(CHANNEL_NAME), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(com.example.stockit.util.AppLanguage.L(CHANNEL_DESC));
            // TM-43 : pas de détail de stock sur l'écran de verrouillage.
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // TM-43 : le détail est masqué sur l'écran de verrouillage.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true);

        notificationManager.notify(notificationId, builder.build());
    }
}
