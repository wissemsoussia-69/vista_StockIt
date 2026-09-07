package com.example.stockit.controller;

import android.content.Context;
import android.util.Log;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AssetTicketCoordinator {

    
    public static class ITAsset {
        public String id;
        public String type; // e.g. "Screen", "Laptop"
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
        public String priority; // CRITICAL, HIGH, LOW
        public long slaTimestamp; // Resolution deadline
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

    public void coordinateAssetReceipt(ITAsset scanneddAsset, List<Ticket> activeTickets, Technician currentTech) {
        Log.i("AssetCoordinator", "--- Coordination started for: " + scanneddAsset.type + " ---");

        List<Ticket> matchingTickets = activeTickets.stream()
                .filter(t -> t.neededAssetType.equalsIgnoreCase(scanneddAsset.type))
                .filter(t -> t.status.equals("OPEN"))
                .collect(Collectors.toList());

        if (matchingTickets.isEmpty()) {
            Log.d("AssetCoordinator", "No pending ticket for this asset type.");
            return;
        }

        Collections.sort(matchingTickets, new Comparator<Ticket>() {
            @Override
            public int compare(Ticket t1, Ticket t2) {
                int p1 = getPriorityWeight(t1.priority);
                int p2 = getPriorityWeight(t2.priority);
                if (p1 != p2) return Integer.compare(p2, p1); // Higher weight first
                return Long.compare(t1.slaTimestamp, t2.slaTimestamp); // Older SLA first
            }
        });

        Ticket topTicket = matchingTickets.get(0);
        topTicket.status = "ASSOCIATED";
        
        Log.i("AssetCoordinator", "Match found! Ticket: " + topTicket.id + " (" + topTicket.priority + ")");

        sendConfirmationEmail(topTicket, scanneddAsset, currentTech);

        triggerUrgentNotification(topTicket, scanneddAsset);
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
        String subject = "[StockIT] Priority assignment: Ticket #" + ticket.id;
        String body = "Hello Manager,\n\nThe asset " + asset.type + " (" + asset.model + ") " +
            "received by " + tech.name + " was automatically reserved for CRITICAL ticket " +
            "'" + ticket.subject + "'.\n\nStatus: Ready for deployment.";

        Log.d("AssetCoordinator_Email", ">>> MANAGER EMAIL SEND <<<\nSubject: " + subject + "\nBody: " + body);

        com.example.stockit.util.StockItReporter.sendEvent(context,
                "CRITICAL ticket: asset reserved (#" + ticket.id + ")",
                "Priority asset automatically assigned to a CRITICAL ticket.\n\n"
                    + "- Ticket        : #" + ticket.id + "\n"
                    + "- Subject       : " + ticket.subject + "\n"
                    + "- Asset         : " + asset.type + " (" + asset.model + ")\n"
                    + "- Received by   : " + tech.name + "\n\n"
                    + "Status: Ready for deployment.",
                "wissem.soussia@vista.com");
    }

    private void triggerUrgentNotification(Ticket ticket, ITAsset asset) {
        String msg = "URGENT: Asset for ticket #" + ticket.id + " (" + ticket.priority + ") has arrived!";

        NotificationHelper.showNotification(context, "Action Required - Support", msg, (int)System.currentTimeMillis());
        Log.w("AssetCoordinator_Push", "Notification sent to technician.");

        com.example.stockit.util.StockItReporter.sendEvent(context,
                "URGENT: support action required (#" + ticket.id + ")",
                "The requested asset for an urgent ticket has arrived.\n\n"
                    + "- Ticket : #" + ticket.id + " (" + ticket.priority + ")\n"
                    + "- Asset  : " + asset.type + " (" + asset.model + ")\n\n"
                    + "Support action required immediately.",
                "wissem.soussia@vista.com");
    }
}
