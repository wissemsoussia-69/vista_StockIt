package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.example.stockit.util.Auth0Manager;
import com.example.stockit.util.SessionManager;

/**
 * StockIT — Écran de lancement.
 *
 * Point d'entrée unique de l'application. Il :
 *   1. affiche le splash Vista,
 *   2. interroge {@link Auth0Manager} pour savoir si une session SSO valide existe,
 *   3. redirige vers {@link MainActivity} (session OK) ou {@link LoginActivity} (SSO requis).
 *
 * Aucun accès direct au scanner ou aux écrans métier n'est possible sans passer par ici.
 */
public class SplashActivity extends AppCompatActivity {

    /** Délai minimum d'affichage du splash pour éviter un "flash" désagréable. */
    private static final long MIN_SPLASH_DURATION_MS = 700L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Le thème Splash a déjà été appliqué via le manifest ; on rebascule
        // sur le thème normal juste avant d'inflater le layout enrichi.
        setTheme(R.style.Theme_StockIT);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        long start = System.currentTimeMillis();

        Auth0Manager auth0 = Auth0Manager.get(getApplicationContext());
        // Arme la biométrie (no-op si BuildConfig.AUTH0_REQUIRE_BIOMETRIC=false).
        // Doit être fait AVANT hasValidSession pour que le prompt biométrique
        // soit demandé lors du déchiffrement des tokens.
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
            // Aucune session Okta : on force le passage par le login SSO.
            SessionManager.get(this).clear();
            next = new Intent(this, LoginActivity.class);
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(next);
        finish();
    }
}
