package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "claims")
public class Claim {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String subject;
    private String description;
    private String senderName;
    private String priority; // BASSE, MOYENNE, HAUTE
    private String status;   // OUVERT, EN_COURS, RESOLU
    private long timestamp;

    public Claim() {}

    @androidx.room.Ignore
    public Claim(String subject, String description, String senderName, String priority) {
        this.subject = subject;
        this.description = description;
        this.senderName = senderName;
        this.priority = priority;
        this.status = "OUVERT";
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
