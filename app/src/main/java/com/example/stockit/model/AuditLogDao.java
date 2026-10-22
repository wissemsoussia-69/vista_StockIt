package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    List<AuditLog> getAll();

    @Insert
    void insert(AuditLog log);
}
