package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "users", indices = {@androidx.room.Index(value = {"username"}, unique = true)})
public class User {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String username;
    private String password;
    private String role; // "ADMIN", "MANAGER", "VIEWER"
    private int points;
    private int level;
    private String badges; // Stockes sous forme de chaine separee par des virgules

    public User(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.points = 0;
        this.level = 1;
        this.badges = "";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getBadges() { return badges; }
    public void setBadges(String badges) { this.badges = badges; }
}
