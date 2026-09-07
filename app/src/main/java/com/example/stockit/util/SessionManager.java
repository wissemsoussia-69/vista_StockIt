package com.example.stockit.util;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionManager {

    private static final String PREFS_NAME = "stockit_session";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_USERNAME  = "username";
    private static final String KEY_EMAIL     = "email";
    private static final String KEY_ROLE      = "role";
    private static final String KEY_PROVIDER  = "provider"; // "auth0" or "local"

    private static volatile SessionManager INSTANCE;

    private final SharedPreferences prefs;

    private SessionManager(Context appContext) {
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static SessionManager get(Context context) {
        if (INSTANCE == null) {
            synchronized (SessionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SessionManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_LOGGED_IN, false);
    }

    public String getUsername() { return prefs.getString(KEY_USERNAME, null); }
    public String getEmail()    { return prefs.getString(KEY_EMAIL, null); }
    public String getRole()     { return prefs.getString(KEY_ROLE, "USER"); }
    public String getProvider() { return prefs.getString(KEY_PROVIDER, "local"); }

    public void saveSsoSession(String username, String email, String role) {
        prefs.edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_USERNAME, username)
                .putString(KEY_EMAIL, email)
                .putString(KEY_ROLE, role != null ? role : "USER")
                .putString(KEY_PROVIDER, "auth0")
                .apply();
    }

    public void saveLocalSession(String username, String role) {
        prefs.edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_USERNAME, username)
                .remove(KEY_EMAIL)
                .putString(KEY_ROLE, role != null ? role : "USER")
                .putString(KEY_PROVIDER, "local")
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
