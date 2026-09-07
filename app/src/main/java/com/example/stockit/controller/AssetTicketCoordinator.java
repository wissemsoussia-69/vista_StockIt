package com.example.stockit.controller;

import android.content.Context;
import android.util.Log;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Classe de Coordination PFE : StockIT (Logistique) <-> Module Support
 * Gère l'attribution automatique du matériel reçu aux tickets prioritaires.
 */
public class AssetTicketCoordinator {

    // --- MODÈLES DE DONNÉES DE BASE (DEMANDÉS) ---
    
    public static class ITAsset {
        public String id;
        public String type; // ex: "Ecran", "Laptop"
        public String model;
        public String serialNumber;

        public ITAsset(String id, String type, String model, String serialNumber) {
            this.id = id; this.type = type; this.model = model; this.serialNumber = serialNumber;
        }
    }

    public static class Ticket {
        public String id;
        public String subject;
        public String neededAssetType;
        public String priority; // CRITIQUE, HAUTE, BASSE
        public long slaTimestamp; // Date limite de résolution
        public String status; // OPEN, ASSOCIATED

        public Ticket(String id, String subject, String neededAssetType, String priority, long sla) {
            this.id = id; this.subject = subject; this.neededAssetType = neededAssetType;
            this.priority = priority; this.slaTimestamp = sla; this.status = "OPEN";
        }
    }

    public static class Technician {
        public String name;
        public String employeeId;
        public Technician(String name, String id) { this.name = name; this.employeeId = id; }
    }

    private final Context context;

    public AssetTicketCoordinator(Context context) {
        this.context = context;
    }

    /**
     * Méthode Principale : Coordonne la réception d'un asset avec les tickets ouverts.
     */
    public void coordinateAssetReceipt(ITAsset scannedAsset, List<Ticket> activeTickets, Technician currentTech) {
        Log.i("AssetCoordinator", "--- Début Coordination pour : " + scannedAsset.type + " ---");

        // 1. Filtrage : On ne garde que les tickets qui ont besoin de ce type de matériel
        List<Ticket> matchingTickets = activeTickets.stream()
                .filter(t -> t.neededAssetType.equalsIgnoreCase(scannedAsset.type))
                .filter(t -> t.status.equals("OPEN"))
                .collect(Collectors.toList());

        if (matchingTickets.isEmpty()) {
            Log.d("AssetCoordinator", "Aucun ticket en attente pour ce type de matériel.");
            return;
        }

        // 2. Triage intelligent : Priorité d'abord, puis SLA le plus proche (plus ancien timestamp)
        Collections.sort(matchingTickets, new Comparator<Ticket>() {
            @Override
            public int compare(Ticket t1, Ticket t2) {
                int p1 = getPriorityWeight(t1.priority);
                int p2 = getPriorityWeight(t2.priority);
                if (p1 != p2) return Integer.compare(p2, p1); // Plus haut poids d'abord
                return Long.compare(t1.slaTimestamp, t2.slaTimestamp); // Plus ancien SLA d'abord
            }
        });

        // 3. Association : On prend le ticket le plus urgent
        Ticket topTicket = matchingTickets.get(0);
        topTicket.status = "ASSOCIATED";
        
        Log.i("AssetCoordinator", "✅ CORRESPONDANCE TROUVÉE ! Ticket: " + topTicket.id + " (" + topTicket.priority + ")");

        // 4. Simulation d'envoi d'e-mail au manager
        sendConfirmationEmail(topTicket, scannedAsset, currentTech);

        // 5. Notification d'action urgente pour le technicien
        triggerUrgentNotification(topTicket, scannedAsset);
    }

    private int getPriorityWeight(String priority) {
        switch (priority.toUpperCase()) {
            case "CRITIQUE": return 3;
            case "HAUTE": return 2;
            case "BASSE": return 1;
            default: return 0;
        }
    }

    private void sendConfirmationEmail(Ticket ticket, ITAsset asset, Technician tech) {
        String subject = "[StockIT] Attribution Prioritaire : Ticket #" + ticket.id;
        String body = "Bonjour Manager,\n\nLe matériel " + asset.type + " (" + asset.model + ") " +
                "réceptionné par " + tech.name + " a été automatiquement réservé pour le ticket " +
                "CRITIQUE : '" + ticket.subject + "'.\n\nStatut : Prêt pour déploiement.";

        Log.d("AssetCoordinator_Email", ">>> ENVOI EMAIL MANAGER <<<\nObjet: " + subject + "\nCorps: " + body);

        // Envoi réel via workflow n8n — destinataire dédié "manager support".
        com.example.stockit.util.StockItReporter.sendEvent(context,
                "Ticket CRITIQUE : matériel réservé (#" + ticket.id + ")",
                "🎯 Matériel prioritaire attribué automatiquement à un ticket CRITIQUE.\n\n"
                        + "• Ticket           : #" + ticket.id + "\n"
                        + "• Sujet du ticket  : " + ticket.subject + "\n"
                        + "• Équipement       : " + asset.type + " (" + asset.model + ")\n"
                        + "• Réceptionné par  : " + tech.name + "\n\n"
                        + "Statut : prêt pour déploiement.",
                "wissem.soussia@vista.com");
    }

    private void triggerUrgentNotification(Ticket ticket, ITAsset asset) {
        String msg = "🚨 URGENT : Le matériel pour le ticket #" + ticket.id + " (" + ticket.priority + ") est arrivé !";

        NotificationHelper.showNotification(context, "Action Requise - Support", msg, (int)System.currentTimeMillis());
        Log.w("AssetCoordinator_Push", "Notification envoyée au technicien.");

        // Email de synthèse à l'équipe support pour tracer l'événement urgent.
        com.example.stockit.util.StockItReporter.sendEvent(context,
                "URGENT : action support requise (#" + ticket.id + ")",
                "🚨 Le matériel demandé pour un ticket urgent est arrivé.\n\n"
                        + "• Ticket     : #" + ticket.id + " (" + ticket.priority + ")\n"
                        + "• Équipement : " + asset.type + " (" + asset.model + ")\n\n"
                        + "👉 Action support requise sans délai.",
                "wissem.soussia@vista.com");
    }
}
