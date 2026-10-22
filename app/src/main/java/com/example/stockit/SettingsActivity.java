package com.example.stockit;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

import com.example.stockit.controller.MainController;
import com.example.stockit.util.Auth0Manager;
import com.example.stockit.util.SharedBridge;
import com.example.stockit.util.WebhookOutbox;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * Paramètres (identique iOS SettingsView) : mode sombre, mode test, état des
 * intégrations, test Slack, file d'attente n8n, version.
 */
public class SettingsActivity extends SimpleScreenActivity {

    public static final String PREFS = "stockit_settings";
    public static final String KEY_DARK = "dark_mode";

    @Override protected String screenTitle() { return "Paramètres"; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- Préférences
        LinearLayout prefs = section("Préférences");
        SwitchMaterial dark = new SwitchMaterial(this);
        dark.setText("Mode sombre");
        dark.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DARK, false));
        dark.setOnCheckedChangeListener((b, on) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DARK, on).apply();
            AppCompatDelegate.setDefaultNightMode(on ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        prefs.addView(dark);

        // --- Mode test
        LinearLayout test = section("Mode test");
        SwitchMaterial testSw = new SwitchMaterial(this);
        testSw.setText("🧪 Mode test");
        testSw.setChecked(SharedBridge.isTestMode(this));
        testSw.setOnCheckedChangeListener((b, on) -> {
            SharedBridge.setTestMode(this, on);
            Toast.makeText(this, on ? "Mode test activé" : "Mode test désactivé", Toast.LENGTH_SHORT).show();
        });
        test.addView(testSw);
        text(test, "Aucun ticket Jira créé, aucun message Slack, aucun e-mail ni webhook n8n. "
                + "Claude et la lecture des tickets restent actifs. Les changements de stock restent "
                + "enregistrés sur le téléphone.", 12, false, R.color.vista_navy_muted);

        // --- Intégrations
        LinearLayout integ = section("Intégrations");
        status(integ, "Cimpress Gateway (IA)", SharedBridge.isCimpressConfigured());
        status(integ, "Claude direct (IA)", SharedBridge.isClaudeConfigured());
        if (!SharedBridge.isAIConfigured()) {
            text(integ, "Mode hors ligne actif : ML Kit + analyse locale du stock.", 12, false, R.color.vista_navy_muted);
        }
        status(integ, "Jira", SharedBridge.isJiraConfigured());
        status(integ, "Jira Assets (CMDB " + SharedBridge.jiraAssetsSchemaId() + ")", SharedBridge.isJiraAssetsConfigured());
        status(integ, "Slack", SharedBridge.isSlackConfigured());
        MaterialButton slackBtn = new MaterialButton(this);
        slackBtn.setText("Tester Slack");
        integ.addView(slackBtn);
        TextView slackRes = text(integ, "", 12, false, R.color.vista_navy_muted);
        slackBtn.setOnClickListener(v -> {
            slackBtn.setEnabled(false);
            slackRes.setText("Envoi…");
            com.example.stockit.model.User u = MainController.getCurrentUser();
            SharedBridge.slackTest(u != null ? u.getUsername() : null, (ok, detail) -> {
                slackBtn.setEnabled(true);
                slackRes.setText(ok ? "✅ Message envoyé dans #etx-alerts" : "❌ " + (detail != null ? detail : "erreur réseau"));
                slackRes.setTextColor(ok ? 0xFF2E9E6B : 0xFFFF6B4A);
            });
        });
        status(integ, "n8n — e-mails (send)", !SharedBridge.sendWebhookUrl().isEmpty());
        status(integ, "n8n — mouvements (event)", !SharedBridge.eventWebhookUrl().isEmpty());
        status(integ, "n8n — statut actifs (asset-status)", !SharedBridge.assetStatusWebhookUrl().isEmpty());
        status(integ, "Vista SSO (Auth0)", Auth0Manager.get(this).isConfigured());

        // --- Outbox
        LinearLayout out = section("Automatisations en attente");
        TextView pending = row(out, "Envois n8n en file d'attente", String.valueOf(WebhookOutbox.pendingCount(this)),
                getColor(R.color.vista_blue));
        MaterialButton resend = new MaterialButton(this);
        resend.setText("Renvoyer maintenant");
        resend.setOnClickListener(v -> {
            resend.setEnabled(false);
            WebhookOutbox.flush(this, n -> {
                resend.setEnabled(true);
                pending.setText(String.valueOf(n));
                Toast.makeText(this, n == 0 ? "File vide ✅" : n + " envoi(s) toujours en attente", Toast.LENGTH_SHORT).show();
            });
        });
        out.addView(resend);

        // --- Version
        LinearLayout ver = section("Application");
        row(ver, "Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", getColor(R.color.vista_navy_muted));
        com.example.stockit.model.User u = MainController.getCurrentUser();
        if (u != null) row(ver, "Connecté", u.getUsername() + " · " + u.getRole(), getColor(R.color.vista_navy_muted));
    }

    private void status(LinearLayout parent, String label, boolean ok) {
        row(parent, label, ok ? "✅ Configuré" : "⚠️ Non configuré", okColor(this, ok));
    }
}
