package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "quests")
public class Quest {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String title;
    private String description;
    private int goalCount;
    private int currentCount;
    private int pointReward;
    private String badgeReward;
    private boolean isCompleted;

    public Quest(String title, String description, int goalCount, int pointReward, String badgeReward) {
        this.title = title;
        this.description = description;
        this.goalCount = goalCount;
        this.currentCount = 0;
        this.pointReward = pointReward;
        this.badgeReward = badgeReward;
        this.isCompleted = false;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getGoalCount() { return goalCount; }
    public void setGoalCount(int goalCount) { this.goalCount = goalCount; }
    public int getCurrentCount() { return currentCount; }
    public void setCurrentCount(int currentCount) { this.currentCount = currentCount; }
    public int getPointReward() { return pointReward; }
    public void setPointReward(int pointReward) { this.pointReward = pointReward; }
    public String getBadgeReward() { return badgeReward; }
    public void setBadgeReward(String badgeReward) { this.badgeReward = badgeReward; }
    public boolean isCompleted() { return isCompleted; }
    public void setCompleted(boolean completed) { isCompleted = completed; }
}
