package com.example.stockit.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "suppliers")
public class Supplier {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String name;
    private String email;
    private String phone;
    private String address;
    private int leadTime; // Délai de livraison en jours
    private double discount; // % réduction

    public Supplier(String name, String email, String phone, String address, int leadTime, double discount) {
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.address = address;
        this.leadTime = leadTime;
        this.discount = discount;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public int getLeadTime() { return leadTime; }
    public void setLeadTime(int leadTime) { this.leadTime = leadTime; }
    public double getDiscount() { return discount; }
    public void setDiscount(double discount) { this.discount = discount; }

    @Override
    public String toString() {
        return name;
    }
}
