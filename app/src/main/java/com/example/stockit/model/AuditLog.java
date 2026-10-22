package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "audit_logs")
public class AuditLog {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String userId;
    private String action; // "ADD_PRODUCT", "UPDATE_QUANTITY", etc.
    private String details;
    private long timestamp;
    private String ipAddress;
    private String device;

    public AuditLog(String userId, String action, String details, long timestamp, String ipAddress, String device) {
        this.userId = userId;
        this.action = action;
        this.details = details;
        this.timestamp = timestamp;
        this.ipAddress = ipAddress;
        this.device = device;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUserId() { return userId; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public long getTimestamp() { return timestamp; }
    public String getIpAddress() { return ipAddress; }
    public String getDevice() { return device; }
}
