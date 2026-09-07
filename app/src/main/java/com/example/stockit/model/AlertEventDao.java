package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AlertEventDao {
    @Insert
    long insert(AlertEvent event);

    @Query("UPDATE alert_events SET status = :status, details = :details, updatedAt = :updatedAt WHERE id = :id")
    void updateStatus(long id, String status, String details, long updatedAt);

    @Query("SELECT * FROM alert_events ORDER BY createdAt DESC LIMIT :limit")
    List<AlertEvent> getRecent(int limit);
}
