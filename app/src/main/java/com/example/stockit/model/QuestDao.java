package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import java.util.List;

@Dao
public interface QuestDao {
    @Query("SELECT * FROM quests WHERE isCompleted = 0")
    List<Quest> getActiveQuests();

    @Insert
    void insert(Quest quest);

    @Update
    void update(Quest quest);

    @Query("SELECT COUNT(*) FROM quests")
    int getQuestCount();
}
