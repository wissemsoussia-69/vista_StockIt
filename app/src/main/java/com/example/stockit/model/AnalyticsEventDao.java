package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AnalyticsEventDao {

    @Insert
    long insert(AnalyticsEvent event);

    @Query("SELECT * FROM analytics_events WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    List<AnalyticsEvent> peekUnsynced(int limit);

    @Query("UPDATE analytics_events SET synced = 1 WHERE id IN (:ids)")
    void markSynced(List<Long> ids);

    @Query("SELECT COUNT(*) FROM analytics_events WHERE synced = 0")
    int countUnsynced();

    @Query("DELETE FROM analytics_events WHERE synced = 1 AND timestamp < :cutoffMs")
    int purgeSyncedOlderThan(long cutoffMs);
}
