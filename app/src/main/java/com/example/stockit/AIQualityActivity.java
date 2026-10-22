package com.example.stockit;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.example.stockit.shared.ai.AIQuality;
import com.example.stockit.util.SharedBridge;

/** Qualité de l'IA (identique iOS AIQualityView), calculée sur les données du téléphone. */
public class AIQualityActivity extends SimpleScreenActivity {

    @Override protected String screenTitle() { return "Qualité de l'IA"; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProgressBar pb = new ProgressBar(this);
        content.addView(pb);
        SharedBridge.aiQuality(this, q -> {
            content.removeAllViews();
            render(q);
        });
    }

    private void render(AIQuality q) {
        LinearLayout g = section("Score global");
        int score = q.getGlobalScore();
        android.widget.TextView big = text(g, score >= 0 ? score + " %" : "—", 48, true, R.color.vista_navy);
        big.setTextColor(color(score));
        big.setGravity(android.view.Gravity.CENTER);
        text(g, "Pondéré : 40 % acceptation des tickets, 30 % précision des quantités, 30 % reconnaissance.",
                12, false, R.color.vista_navy_muted).setGravity(android.view.Gravity.CENTER);

        LinearLayout t = section("🎫 Répartition sur les tickets Jira");
        metric(t, "Suggestions appliquées", pct(q.getTicketAcceptanceRate()),
                L("%@ / %@ suggestions de Claude", q.getTicketAccepted(), q.getTicketSuggestions()));
        metric(t, "Précision des quantités", pct(q.getQuantityPrecision()), "livré ÷ suggéré, par ticket");
        metric(t, "Affectations manuelles", String.valueOf(q.getManualAssignments()), "choix faits sans l'IA");

        LinearLayout v = section("📷 Reconnaissance d'équipement");
        metric(v, "Nom conservé tel quel", pct(q.getVisionAccuracy()), L("%@ / %@ scans confirmés", q.getVisionKept(), q.getVisionScans()));
        metric(v, "Confiance moyenne annoncée", pct(q.getAvgConfidence()), "auto-évaluation de Claude");
        metric(v, "Scans à faible confiance", String.valueOf(q.getLowConfidenceScans()),
                q.getLowConfidenceScans() > 0 ? L("%@ étaient corrects malgré tout", q.getLowConfidenceKept()) : "aucun");
        metric(v, "Numéros de série lus", String.valueOf(q.getSerialsRead()), "envoyés à Jira Assets");

        LinearLayout n = section("À propos");
        text(n, "Calculé uniquement sur les données de ce téléphone : chaque scan confirmé et chaque sortie sur ticket alimentent ces indicateurs. « — » = pas encore assez de données.", 12, false, R.color.vista_navy_muted);
    }

    private void metric(LinearLayout parent, String title, String value, String sub) {
        row(parent, title, value, getColor(R.color.vista_blue));
        text(parent, sub, 11, false, R.color.vista_navy_muted);
        divider(parent);
    }

    private static String pct(int v) { return v >= 0 ? v + " %" : "—"; }

    private int color(int v) {
        if (v < 0) return getColor(R.color.vista_navy_muted);
        if (v >= 85) return getColor(R.color.vista_mint);
        if (v >= 60) return getColor(R.color.vista_amber);
        return getColor(R.color.vista_coral);
    }
}
