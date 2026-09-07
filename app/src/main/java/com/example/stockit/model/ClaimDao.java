package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface ClaimDao {
    @Query("SELECT * FROM claims ORDER BY timestamp DESC")
    List<Claim> getAll();

    @Query("SELECT * FROM claims WHERE senderName = :username ORDER BY timestamp DESC")
    List<Claim> getByUsername(String username);

    @Insert
    void insert(Claim claim);

    @Update
    void update(Claim claim);

    @Delete
    void delete(Claim claim);
    
    @Query("DELETE FROM claims")
    void deleteAll();
}
