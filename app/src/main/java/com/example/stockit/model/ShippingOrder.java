package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "shipping_orders")
public class ShippingOrder {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String customerName;
    private String itemsSummary; // Product list (e.g. "Laptop x2, Mouse x1")
    private String shippingAddress;
    private String trackingNumber;
    private String ticketNumber; // Nouveau champ
    private String status; // "PENDING", "PREPARING", "SHIPPED", "DELIVERED"

    public ShippingOrder(String customerName, String itemsSummary, String shippingAddress, String trackingNumber, String status) {
        this.customerName = customerName;
        this.itemsSummary = itemsSummary;
        this.shippingAddress = shippingAddress;
        this.trackingNumber = trackingNumber;
        this.status = status;
        this.ticketNumber = "";
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getItemsSummary() { return itemsSummary; }
    public void setItemsSummary(String itemsSummary) { this.itemsSummary = itemsSummary; }
    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
