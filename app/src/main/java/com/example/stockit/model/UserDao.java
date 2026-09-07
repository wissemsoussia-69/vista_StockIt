package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

@Dao
public interface UserDao {
    @Query("SELECT * FROM users WHERE username = :username AND password = :password LIMIT 1")
    User login(String username, String password);

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void register(User user);

    @androidx.room.Update
    void update(User user);

    @Query("SELECT * FROM users")
    java.util.List<User> getAllUsers();

    @Query("SELECT COUNT(*) FROM users")
    int getUserCount();

    @androidx.room.Delete
    void deleteUser(User user);
}
