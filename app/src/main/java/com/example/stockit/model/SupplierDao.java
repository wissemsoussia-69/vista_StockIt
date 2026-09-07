package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface SupplierDao {
    @Query("SELECT * FROM suppliers")
    List<Supplier> getAll();

    @Insert
    void insert(Supplier supplier);

    @Update
    void update(Supplier supplier);

    @Delete
    void delete(Supplier supplier);
}
