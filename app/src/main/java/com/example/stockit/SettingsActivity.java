package com.example.stockit;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

import com.example.stockit.controller.MainController;
import com.example.stockit.util.AppLanguage;
import com.example.stockit.util.AppSession;
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

    private MainController controller;

    @Override protected String screenTitle() { return "Paramètres"; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new MainController(this);

        // --- Préférences
        LinearLayout prefs = section("Préférences");
        SwitchMaterial dark = new SwitchMaterial(this);
        dark.setText(L("Mode sombre"));
        dark.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DARK, false));
        dark.setOnCheckedChangeListener((b, on) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DARK, on).apply();
            AppCompatDelegate.setDefaultNightMode(on ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        prefs.addView(dark);

        // Langue de l'application (indépendante du téléphone) — identique iOS « Langue ».
        TextView langLabel = text(prefs, getString(R.string.settings_language), 14, true, R.color.vista_navy);
        langLabel.setPadding(0, dp(12), 0, dp(4));
        android.widget.RadioGroup lang = new android.widget.RadioGroup(this);
        lang.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        String current = AppLanguage.current(this);
        for (String code : AppLanguage.codes()) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(AppLanguage.displayName(code));
            rb.setTag(code);
            rb.setPadding(0, 0, dp(16), 0);
            lang.addView(rb);
            if (code.equals(current)) rb.setChecked(true);
        }
        lang.setOnCheckedChangeListener((g, id) -> {
            View rb = g.findViewById(id);
            if (rb == null || rb.getTag() == null) return;
            String code = (String) rb.getTag();
            if (code.equals(AppLanguage.current(this))) return;
            // L'interface est retraduite immédiatement (les écrans ouverts sont recréés).
            AppLanguage.set(this, code);
        });
        prefs.addView(lang);

        // --- Mode test
        LinearLayout test = section("Mode test");
        SwitchMaterial testSw = new SwitchMaterial(this);
        testSw.setText(L("🧪 Mode test"));
        testSw.setChecked(SharedBridge.isTestMode(this));
        testSw.setOnCheckedChangeListener((b, on) -> {
            // TM-17 : réservé à l'ADMIN, tracé dans l'audit, jamais persisté
            // au-delà de la session (désactivé au retour sur l'écran de login).
            controller.setTestMode(on, () -> {
                testSw.setChecked(SharedBridge.isTestMode(this));
                AppSession.updateTestBanner(this);
            });
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
        status(integ, L("Jira Assets (CMDB %@)", SharedBridge.jiraAssetsSchemaId()), SharedBridge.isJiraAssetsConfigured());
        status(integ, "Slack", SharedBridge.isSlackConfigured());
        MaterialButton slackBtn = new MaterialButton(this);
        slackBtn.setText(L("Tester Slack"));
        integ.addView(slackBtn);
        TextView slackRes = text(integ, "", 12, false, R.color.vista_navy_muted);
        slackBtn.setOnClickListener(v -> {
            slackBtn.setEnabled(false);
            slackRes.setText(L("Envoi…"));
            com.example.stockit.model.User u = MainController.getCurrentUser();
            SharedBridge.slackTest(u != null ? u.getUsername() : null, (ok, detail) -> {
                slackBtn.setEnabled(true);
                slackRes.setText(ok ? L("✅ Message envoyé dans #etx-alerts") : "❌ " + (detail != null ? detail : L("erreur réseau")));
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
        resend.setText(L("Renvoyer maintenant"));
        resend.setOnClickListener(v -> {
            resend.setEnabled(false);
            WebhookOutbox.flush(this, n -> {
                resend.setEnabled(true);
                pending.setText(String.valueOf(n));
                Toast.makeText(this, n == 0 ? L("File vide ✅") : L("%@ envoi(s) toujours en attente", n), Toast.LENGTH_SHORT).show();
            });
        });
        out.addView(resend);

        // --- Version
        LinearLayout ver = section("Application");
        row(ver, "Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", getColor(R.color.vista_navy_muted));
        com.example.stockit.model.User u = MainController.getCurrentUser();
        if (u != null) row(ver, "Connecté", u.getUsername() + " · " + AppLanguage.LV(u.getRole()), getColor(R.color.vista_navy_muted));
    }

    private void status(LinearLayout parent, String label, boolean ok) {
        row(parent, label, ok ? L("✅ Configuré") : L("⚠️ Non configuré"), okColor(this, ok));
    }
}
