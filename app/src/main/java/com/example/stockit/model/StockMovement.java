package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "stock_movements")
public class StockMovement {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private int productId;
    private String productName;
    private String type; // "IN" or "OUT"
    private int quantity;
    private String date;
    private String reason;
    private String userName; // Utilisateur ayant effectue l'action
    private String comment; // Commentaire optionnel

    private String ticketId;         // ex "ETXTUN-42"
    private String assignmentReason; // e.g. "urgent 3 headsets" (AI or manual reason)

    public StockMovement(int productId, String productName, String type, int quantity, String date, String reason, String userName, String comment) {
        this.productId = productId;
        this.productName = productName;
        this.type = type;
        this.quantity = quantity;
        this.date = date;
        this.reason = reason;
        this.userName = userName;
        this.comment = comment;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public int getProductId() { return productId; }
    public void setProductId(int id) { this.productId = id; }
    public String getProductName() { return productName; }
    public void setProductName(String name) { this.productName = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String ticketId) { this.ticketId = ticketId; }
    public String getAssignmentReason() { return assignmentReason; }
    public void setAssignmentReason(String assignmentReason) { this.assignmentReason = assignmentReason; }
}
