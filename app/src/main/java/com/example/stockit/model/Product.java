package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "products")
public class Product {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String name;
    private String category; // Nom pour affichage rapide
    private Integer categoryId; // Relation avec la table catégories
    private String description;
    private String assetTag;
    private int quantity;
    private double unitPrice;
    private String manufacturingDate;
    private String expirationDate;
    private int minThreshold;

    // --- StockIT PFE : lien avec le PO scanné via facture ---
    private String poNumber;      // ex "PO-1234"
    private String poDescription; // ex "Écrans Dell 24 pouces, 5 unités"
    private String receivedFrom;  // ex "Dell Technologies" (fournisseur extrait de la facture)

    // --- StockIT PFE : données extraites de l'étiquette carton ---
    private String articleNumber;   // ex "1001421"
    private String brand;           // ex "EPOS"
    private String packagePoNumber; // ex "3480" (PO imprimé sur le carton, pour cross-check)

    public Product(String name, String category, String description, String assetTag, int quantity, double unitPrice, String manufacturingDate, String expirationDate) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.assetTag = assetTag;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.manufacturingDate = manufacturingDate;
        this.expirationDate = expirationDate;
        this.minThreshold = 5;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAssetTag() { return assetTag; }
    public void setAssetTag(String assetTag) { this.assetTag = assetTag; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
    public String getManufacturingDate() { return manufacturingDate; }
    public void setManufacturingDate(String manufacturingDate) { this.manufacturingDate = manufacturingDate; }
    public String getExpirationDate() { return expirationDate; }
    public void setExpirationDate(String expirationDate) { this.expirationDate = expirationDate; }
    public int getMinThreshold() { return minThreshold; }
    public void setMinThreshold(int minThreshold) { this.minThreshold = minThreshold; }

    // --- PFE PO linkage ---
    public String getPoNumber() { return poNumber; }
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }
    public String getPoDescription() { return poDescription; }
    public void setPoDescription(String poDescription) { this.poDescription = poDescription; }
    public String getReceivedFrom() { return receivedFrom; }
    public void setReceivedFrom(String receivedFrom) { this.receivedFrom = receivedFrom; }

    // --- PFE Package label ---
    public String getArticleNumber() { return articleNumber; }
    public void setArticleNumber(String articleNumber) { this.articleNumber = articleNumber; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getPackagePoNumber() { return packagePoNumber; }
    public void setPackagePoNumber(String packagePoNumber) { this.packagePoNumber = packagePoNumber; }
}
