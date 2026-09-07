package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "analyzed_tickets",
        indices = {@Index(value = {"ticketId", "equipmentKey"}, unique = true)}
)
public class AnalyzedTicket {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String ticketId;       // e.g. "SD-234398"
    private String equipmentKey;   // normalized equipment name in lowercase (e.g. "mouse")
    private String equipmentName;  // readable equipment name (e.g. "Mouse")
    private int    suggestedQty;   // quantity suggested by the LLM (0 when manually assigned without AI)
    private int    deliveredQty;   // actually delivered quantity
    private String reason;         // LLM reason or "Manual selection"
    private boolean fulfilled;     // deliveredQty >= suggestedQty (and suggestedQty > 0)
    private long   analyzedAt;     // epoch ms
    private long   fulfilledAt;    // epoch ms, 0 if not fulfilled
    private String jiraUpdated;    // Jira `updated` field at analysis time
    private String ticketSummary;  // for debug/display without Jira re-fetch

    public AnalyzedTicket() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }

    public String getEquipmentKey() { return equipmentKey; }
    public void setEquipmentKey(String equipmentKey) { this.equipmentKey = equipmentKey; }

    public String getEquipmentName() { return equipmentName; }
    public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }

    public int getSuggestedQty() { return suggestedQty; }
    public void setSuggestedQty(int suggestedQty) { this.suggestedQty = suggestedQty; }

    public int getDeliveredQty() { return deliveredQty; }
    public void setDeliveredQty(int deliveredQty) { this.deliveredQty = deliveredQty; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public boolean isFulfilled() { return fulfilled; }
    public void setFulfilled(boolean fulfilled) { this.fulfilled = fulfilled; }

    public long getAnalyzedAt() { return analyzedAt; }
    public void setAnalyzedAt(long analyzedAt) { this.analyzedAt = analyzedAt; }

    public long getFulfilledAt() { return fulfilledAt; }
    public void setFulfilledAt(long fulfilledAt) { this.fulfilledAt = fulfilledAt; }

    public String getJiraUpdated() { return jiraUpdated; }
    public void setJiraUpdated(String jiraUpdated) { this.jiraUpdated = jiraUpdated; }

    public String getTicketSummary() { return ticketSummary; }
    public void setTicketSummary(String ticketSummary) { this.ticketSummary = ticketSummary; }
}
