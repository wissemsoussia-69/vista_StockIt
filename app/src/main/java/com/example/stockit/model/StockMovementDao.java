package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface StockMovementDao {
    @Query("SELECT * FROM stock_movements ORDER BY date DESC")
    List<StockMovement> getAll();

    @Query("SELECT * FROM stock_movements WHERE userName = :username ORDER BY date DESC")
    List<StockMovement> getByUser(String username);

    @Query("SELECT * FROM stock_movements WHERE productId = :productId ORDER BY date DESC")
    List<StockMovement> getByProduct(int productId);

    @Insert
    void insert(StockMovement movement);
}
