package com.example.stockit;

import android.app.Application;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.stockit.controller.ReportWorker;
import com.example.stockit.util.SharedBridge;
import com.example.stockit.util.WebhookOutbox;

import java.util.concurrent.TimeUnit;

/**
 * StockIT — initialisation globale :
 *  - module partagé KMP (même logique métier que l'app iOS),
 *  - rapport de stock quotidien (WorkManager → webhook n8n),
 *  - renvoi des webhooks n8n restés en attente.
 */
public class StockItApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        com.example.stockit.util.SessionManager.get(this);
        boolean dark = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).getBoolean(SettingsActivity.KEY_DARK, false);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(dark
                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        try {
            SharedBridge.init(this);
        } catch (Throwable t) {
            android.util.Log.e("StockItApp", "Init module partagé KO", t);
        }

        try {
            PeriodicWorkRequest daily = new PeriodicWorkRequest.Builder(ReportWorker.class, 1, TimeUnit.DAYS)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build();
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "stockit_daily_report", ExistingPeriodicWorkPolicy.KEEP, daily);
        } catch (Throwable t) {
            android.util.Log.e("StockItApp", "Planification rapport KO", t);
        }

        WebhookOutbox.flush(this, null);
    }
}
