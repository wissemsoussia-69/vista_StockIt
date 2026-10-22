package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface PurchaseOrderDao {
    @Query("SELECT * FROM purchase_orders ORDER BY date DESC")
    List<PurchaseOrder> getAll();

    @Insert
    void insert(PurchaseOrder order);

    @Update
    void update(PurchaseOrder order);

    @androidx.room.Delete
    void delete(PurchaseOrder order);
}
