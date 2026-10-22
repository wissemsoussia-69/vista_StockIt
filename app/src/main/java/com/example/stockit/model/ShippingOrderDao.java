package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface ShippingOrderDao {
    @Query("SELECT * FROM shipping_orders ORDER BY id DESC")
    List<ShippingOrder> getAll();

    @Insert
    void insert(ShippingOrder order);

    @Update
    void update(ShippingOrder order);

    @Delete
    void delete(ShippingOrder order);
}
