package com.example.stockit.model;

public class AIInsight {
    public enum Type { PREDICTION, RECOMMENDATION, ANOMALY, PRICE_SUGGESTION, RISK }
    
    private String title;
    private String description;
    private Type type;
    private String priority; // "HIGH", "MEDIUM", "LOW"

    public AIInsight(String title, String description, Type type, String priority) {
        this.title = title;
        this.description = description;
        this.type = type;
        this.priority = priority;
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Type getType() { return type; }
    public String getPriority() { return priority; }
}
