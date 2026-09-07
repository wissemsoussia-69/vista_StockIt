package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "categories")
public class Category {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String name;
    private Integer parentId; // Pour les sous-catégories imbriquées
    private String labelColor; // Étiquettes personnalisables (Ex: #00AEEF)

    public Category(String name, Integer parentId, String labelColor) {
        this.name = name;
        this.parentId = parentId;
        this.labelColor = labelColor;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }
    public String getLabelColor() { return labelColor; }
    public void setLabelColor(String labelColor) { this.labelColor = labelColor; }

    @Override
    public String toString() {
        return name; // Utilisé par les Adapters de Spinner
    }
}
