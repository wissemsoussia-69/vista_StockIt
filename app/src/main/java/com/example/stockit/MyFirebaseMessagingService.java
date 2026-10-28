package com.example.stockit;

import androidx.annotation.NonNull;
import com.example.stockit.controller.NotificationHelper;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Push FCM : affichage local uniquement.
 *
 * TM-29 : le contenu d'un push n'est PLUS retransmis par e-mail via n8n — le
 * contenu d'une notification ne doit jamais quitter l'appareil sans action de
 * l'utilisateur (divulgation d'informations + journalisation injustifiée).
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        if (remoteMessage.getNotification() != null) {
            String title = remoteMessage.getNotification().getTitle();
            String body  = remoteMessage.getNotification().getBody();
            NotificationHelper.showNotification(this, title, body, 1001);
        }
    }
}
