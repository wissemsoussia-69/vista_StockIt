package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * StockIT PFE — Cache d'analyse des tickets Jira.
 *
 * À chaque suggestion du LLM (Claude Opus 4) et à chaque affectation manuelle,
 * on garde une trace de :
 *   - la quantité que le LLM avait proposée
 *   - la quantité réellement livrée
 *   - la raison (analyse texte du LLM ou "Choix manuel")
 *   - le champ `updated` de Jira au moment de l'analyse (invalidation cache)
 *
 * Quand `deliveredQty >= suggestedQty` on marque `fulfilled = true`
 * et le ticket ne sera plus proposé au LLM la fois suivante.
 *
 * Unique par couple (ticketId, equipmentKey) : un même ticket peut demander
 * plusieurs types d'équipements (ex : 3 souris + 2 claviers).
 */
@Entity(
        tableName = "analyzed_tickets",
        indices = {@Index(value = {"ticketId", "equipmentKey"}, unique = true)}
)
public class AnalyzedTicket {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String ticketId;       // ex "SD-234398"
    private String equipmentKey;   // nom équipement normalisé lowercase (ex "souris")
    private String equipmentName;  // nom équipement lisible (ex "Souris")
    private int    suggestedQty;   // qté proposée par le LLM (0 si affectation manuelle sans IA)
    private int    deliveredQty;   // qté réellement sortie
    private String reason;         // raison LLM ou "Choix manuel"
    private boolean fulfilled;     // deliveredQty >= suggestedQty (et suggestedQty > 0)
    private long   analyzedAt;     // epoch ms
    private long   fulfilledAt;    // epoch ms, 0 si non fulfilled
    private String jiraUpdated;    // champ Jira `updated` au moment de l'analyse
    private String ticketSummary;  // pour debug / affichage sans re-fetch Jira

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
