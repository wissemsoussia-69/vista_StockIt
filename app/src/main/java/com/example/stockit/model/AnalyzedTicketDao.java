package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AnalyzedTicketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(AnalyzedTicket t);

    @Update
    void update(AnalyzedTicket t);

    @Query("SELECT * FROM analyzed_tickets WHERE ticketId = :ticketId AND equipmentKey = :equipmentKey LIMIT 1")
    AnalyzedTicket getByTicketAndEquipment(String ticketId, String equipmentKey);

    @Query("SELECT * FROM analyzed_tickets WHERE ticketId = :ticketId")
    List<AnalyzedTicket> getByTicket(String ticketId);

    /**
     * Tickets entièrement livrés : au moins une ligne fulfilled=1
     * et aucune ligne non-fulfilled pour ce ticketId.
     */
    @Query("SELECT DISTINCT ticketId FROM analyzed_tickets " +
           "WHERE fulfilled = 1 " +
           "AND ticketId NOT IN (SELECT ticketId FROM analyzed_tickets WHERE fulfilled = 0)")
    List<String> getFullyFulfilledTicketIds();

    @Query("SELECT * FROM analyzed_tickets ORDER BY analyzedAt DESC")
    List<AnalyzedTicket> getAll();

    @Query("DELETE FROM analyzed_tickets WHERE ticketId = :ticketId")
    void deleteByTicket(String ticketId);
}
