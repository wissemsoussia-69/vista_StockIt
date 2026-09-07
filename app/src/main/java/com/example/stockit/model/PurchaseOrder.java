package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "purchase_orders")
public class PurchaseOrder {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String productName;
    private int quantity;
    private String supplier;
    private String date;
    private String status; // "Pending", "Received"

    public PurchaseOrder(String productName, int quantity, String supplier, String date, String status) {
        this.productName = productName;
        this.quantity = quantity;
        this.supplier = supplier;
        this.date = date;
        this.status = status;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
