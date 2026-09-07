package com.example.stockit;

import androidx.annotation.NonNull;
import com.example.stockit.controller.NotificationHelper;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        if (remoteMessage.getNotification() != null) {
            String title = remoteMessage.getNotification().getTitle();
            String body  = remoteMessage.getNotification().getBody();
            NotificationHelper.showNotification(this, title, body, 1001);
            com.example.stockit.util.StockItReporter.sendEvent(this,
                    "Server push received (audit)",
                    "A push notification was successfully delivered to the device.\n\n"
                        + "- Title   : " + (title != null ? title : "(none)") + "\n"
                        + "- Content : " + (body  != null ? body  : "(none)") + "\n\n"
                        + "Automatic echo for notification-chain auditing.",
                    "wissem.soussia@vista.com");
        }
    }
}
