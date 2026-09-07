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
            // Écho au workflow n8n pour tracer côté serveur la réception
            // effective du push (utile pour audit / re-envoi mail agrégé).
            com.example.stockit.util.StockItReporter.sendEvent(this,
                    "Push serveur reçu (audit)",
                    "📩 Une notification push a bien été livrée sur le terminal.\n\n"
                            + "• Titre   : " + (title != null ? title : "(aucun)") + "\n"
                            + "• Contenu : " + (body  != null ? body  : "(aucun)") + "\n\n"
                            + "Écho automatique pour audit de la chaîne de notification.",
                    "wissem.soussia@vista.com");
        }
    }
}
