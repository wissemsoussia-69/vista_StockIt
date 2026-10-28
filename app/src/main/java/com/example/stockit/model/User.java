package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Compte StockIT (Room). Le mot de passe local est un hash PBKDF2
 * (« pbkdf2-sha256$… ») ; les comptes SSO n'en ont pas (password vide) et sont
 * liés au sujet Auth0 immuable (authSubject), jamais au nom affiché.
 */
@Entity(tableName = "users", indices = {
        @Index(value = {"username"}, unique = true),
        @Index(value = {"authSubject"}, unique = true),
})
public class User {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String username;
    /** Hash PBKDF2 pour les comptes locaux, vide pour les comptes SSO. */
    private String password;
    private String role; // "ADMIN", "MANAGER", "TECHNICIEN", "USER"
    /** Sujet Auth0 (sub) pour les comptes SSO, null sinon. */
    private String authSubject;
    /** "local" (mot de passe) ou "auth0" (SSO). */
    private String provider;
    private int points;
    private int level;
    private String badges; // Stockés sous forme de chaîne séparée par des virgules

    public User(String username, String password, String role) {
        this(username, password, role, null, "local");
    }

    @androidx.room.Ignore
    public User(String username, String password, String role, String authSubject, String provider) {
        this.username = username;
        this.password = password == null ? "" : password;
        this.role = role;
        this.authSubject = authSubject;
        this.provider = provider == null || provider.isEmpty() ? "local" : provider;
        this.points = 0;
        this.level = 1;
        this.badges = "";
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password == null ? "" : password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getAuthSubject() { return authSubject; }
    public void setAuthSubject(String authSubject) { this.authSubject = authSubject; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public String getBadges() { return badges; }
    public void setBadges(String badges) { this.badges = badges; }
}
