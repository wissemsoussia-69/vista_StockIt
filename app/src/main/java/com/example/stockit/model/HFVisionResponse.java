package com.example.stockit.model;

public class HFVisionResponse {
    private String label;  // Ex: "computer keyboard", "mouse"
    private double score;  // Ex: 0.98 (pour 98% de certitude)

    public String getLabel() { return label; }
    public double getScore() { return score; }
}
