package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.example.stockit.controller.MainController;
import com.example.stockit.util.Auth0Manager;

/**
 * StockIT — Écran de lancement (comportement identique à l'app iOS).
 *
 * Sécurité : aucune session n'est conservée d'un lancement à l'autre. Les tokens
 * Auth0 locaux sont effacés et l'employé doit toujours se reconnecter avec Okta.
 * Point d'entrée unique : MainActivity n'est pas exportée.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long MIN_SPLASH_DURATION_MS = 700L;
    public static final String ACTION_ADD_PRODUCT = "add_product";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StockIT);
        super.onCreate(savedInstanceState);

        // Déjà connecté dans ce processus (ex. raccourci lancé app ouverte) → direct.
        if (MainController.getCurrentUser() != null) {
            openMain();
            return;
        }

        setContentView(R.layout.activity_splash);
        Auth0Manager.get(getApplicationContext()).clearLocalCredentials();
        MainController.logoutLocal();
        // TM-17 : le mode test ne survit jamais à une session (ni au redémarrage).
        try {
            com.example.stockit.util.SharedBridge.setTestMode(this, false);
        } catch (Throwable ignored) { }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent next = new Intent(this, LoginActivity.class);
            // TM-39 : seul le raccourci documenté est transmis, jamais une action arbitraire.
            if (getIntent() != null && ACTION_ADD_PRODUCT.equals(getIntent().getAction())) {
                next.putExtra(LoginActivity.EXTRA_THEN_ACTION, ACTION_ADD_PRODUCT);
            }
            next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(next);
            finish();
        }, MIN_SPLASH_DURATION_MS);
    }

    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        // TM-39 : seules les actions connues de l'app sont transmises à MainActivity.
        if (getIntent() != null && ACTION_ADD_PRODUCT.equals(getIntent().getAction())) {
            i.setAction(ACTION_ADD_PRODUCT);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }
}
