package com.example.stockit.model;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

/**
 * Accès aux comptes locaux (hash PBKDF2) et SSO (sujet Auth0).
 *
 * La vérification du mot de passe ne se fait PAS en SQL (plus de
 * « WHERE password = ? » en clair) : la ligne est chargée par nom, puis le
 * hash est vérifié côté applicatif (MainController.login + PasswordHasher).
 */
@Dao
public interface UserDao {
    @Query("SELECT * FROM users WHERE username = :username AND provider = 'local' LIMIT 1")
    User findLocalByUsername(String username);

    @Query("SELECT * FROM users WHERE authSubject = :subject LIMIT 1")
    User findByAuthSubject(String subject);

    @Query("SELECT * FROM users WHERE provider = 'auth0' AND (authSubject IS NULL OR authSubject = '') AND username = :username LIMIT 1")
    User findLegacySsoByUsername(String username);

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    void register(User user);

    @androidx.room.Update
    void update(User user);

    @Query("UPDATE users SET authSubject = :subject, provider = 'auth0' WHERE id = :id")
    void bindAuthSubject(String subject, int id);

    @Query("SELECT * FROM users")
    java.util.List<User> getAllUsers();

    @Query("SELECT COUNT(*) FROM users WHERE provider = 'local'")
    int getLocalAccountCount();

    @Query("SELECT COUNT(*) FROM users")
    int getUserCount();

    @androidx.room.Delete
    void deleteUser(User user);
}
