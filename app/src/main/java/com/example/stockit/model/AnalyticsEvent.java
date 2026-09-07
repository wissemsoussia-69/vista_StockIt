package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "analytics_events",
        indices = {@Index("synced"), @Index("eventName")}
)
public class AnalyticsEvent {

    @PrimaryKey(autoGenerate = true)
    private long id;

    private String eventName;

    private String userId;

    private String properties;

    private long timestamp;

    private boolean synced;

    public AnalyticsEvent() {
    }

    @Ignore
    public AnalyticsEvent(String eventName, String userId, String properties, long timestamp) {
        this.eventName = eventName;
        this.userId = userId;
        this.properties = properties;
        this.timestamp = timestamp;
        this.synced = false;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getProperties() { return properties; }
    public void setProperties(String properties) { this.properties = properties; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public boolean isSynced() { return synced; }
    public void setSynced(boolean synced) { this.synced = synced; }
}
