package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "products")
public class Product {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String name;
    private String category; // Name for quick display
    private Integer categoryId; // Relationship with categories table
    private String description;
    private String assetTag;
    private int quantity;
    private double unitPrice;
    private String manufacturingDate;
    private String expirationDate;
    private int minThreshold;

    private String poNumber;      // e.g. "PO-1234"
    private String poDescription; // e.g. "Dell 24-inch monitors, 5 units"
    private String receivedFrom;  // e.g. "Dell Technologies" (supplier extracted from invoice)

    private String articleNumber;   // e.g. "1001421"
    private String brand;           // e.g. "EPOS"
    private String packagePoNumber; // e.g. "3480" (PO printed on the box for cross-check)

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

    public String getPoNumber() { return poNumber; }
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }
    public String getPoDescription() { return poDescription; }
    public void setPoDescription(String poDescription) { this.poDescription = poDescription; }
    public String getReceivedFrom() { return receivedFrom; }
    public void setReceivedFrom(String receivedFrom) { this.receivedFrom = receivedFrom; }

    public String getArticleNumber() { return articleNumber; }
    public void setArticleNumber(String articleNumber) { this.articleNumber = articleNumber; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getPackagePoNumber() { return packagePoNumber; }
    public void setPackagePoNumber(String packagePoNumber) { this.packagePoNumber = packagePoNumber; }
}
