package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "alert_events")
public class AlertEvent {
    @PrimaryKey(autoGenerate = true)
    private long id;

    private String productName;
    private int quantity;
    private int threshold;
    private int dynamicThreshold;
    private int predictedDays;
    private String channel;
    private String status;
    private String message;
    private String details;
    private long createdAt;
    private long updatedAt;

    public AlertEvent(String productName,
                      int quantity,
                      int threshold,
                      int dynamicThreshold,
                      int predictedDays,
                      String channel,
                      String status,
                      String message,
                      String details,
                      long createdAt,
                      long updatedAt) {
        this.productName = productName;
        this.quantity = quantity;
        this.threshold = threshold;
        this.dynamicThreshold = dynamicThreshold;
        this.predictedDays = predictedDays;
        this.channel = channel;
        this.status = status;
        this.message = message;
        this.details = details;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public int getThreshold() { return threshold; }
    public int getDynamicThreshold() { return dynamicThreshold; }
    public int getPredictedDays() { return predictedDays; }
    public String getChannel() { return channel; }
    public String getStatus() { return status; }
    public String getMessage() { return message; }
    public String getDetails() { return details; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; }
    public void setDetails(String details) { this.details = details; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
