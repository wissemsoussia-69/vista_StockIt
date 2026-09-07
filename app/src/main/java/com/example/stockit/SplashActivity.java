package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.example.stockit.util.Auth0Manager;
import com.example.stockit.util.SessionManager;

public class SplashActivity extends AppCompatActivity {

    private static final long MIN_SPLASH_DURATION_MS = 700L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.example.stockit.util.LocaleHelper.restore(this);

        com.example.stockit.util.CrashReporter.install(this);

        setTheme(R.style.Theme_StockIT);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        long start = System.currentTimeMillis();

        Auth0Manager auth0 = Auth0Manager.get(getApplicationContext());
        auth0.enableBiometricIfRequested(this);

        auth0.hasValidSession(hasSession -> {
            long elapsed = System.currentTimeMillis() - start;
            long remaining = Math.max(0L, MIN_SPLASH_DURATION_MS - elapsed);
            new Handler(Looper.getMainLooper()).postDelayed(() -> route(hasSession), remaining);
        });
    }

    private void route(boolean hasValidSession) {
        Intent next;
        if (hasValidSession && SessionManager.get(this).isLoggedIn()) {
            next = new Intent(this, MainActivity.class);
        } else {
            SessionManager.get(this).clear();
            next = new Intent(this, LoginActivity.class);
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(next);
        finish();
    }
}
