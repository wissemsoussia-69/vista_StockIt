package com.example.stockit;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.example.stockit.controller.MainController;
import com.example.stockit.shared.ai.Forecast;
import com.example.stockit.shared.ai.OrderDraft;
import com.example.stockit.util.SharedBridge;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Prévision des besoins + brouillon de commande IA (identique iOS ForecastView / OrderDraftView). */
public class ForecastActivity extends SimpleScreenActivity {

    private MainController controller;
    private List<Forecast> items = new ArrayList<>();
    private boolean showAll = false;
    private LinearLayout list;

    @Override protected String screenTitle() { return "Prévision des besoins"; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new MainController(this);

        LinearLayout head = section("Méthode");
        text(head, SharedBridge.isAIConfigured()
                ? "🧠 Calcul sur les sorties réelles (60 j) + demandes Jira ouvertes, puis analyse par Claude (horizon 30 j)."
                : "Calcul local sur les sorties réelles (60 j) + demandes Jira ouvertes. Configurez l'IA pour l'analyse de Claude.",
                12, false, R.color.vista_navy_muted);
        SwitchMaterial sw = new SwitchMaterial(this);
        sw.setText("Afficher aussi les produits sans risque");
        sw.setOnCheckedChangeListener((b, on) -> { showAll = on; render(); });
        head.addView(sw);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        load();
    }

    private void load() {
        list.removeAllViews();
        ProgressBar pb = new ProgressBar(this);
        list.addView(pb);
        text(list, "Analyse en cours…", 13, false, R.color.vista_navy_muted);
        SharedBridge.forecast(this, true, result -> { items = result; render(); });
    }

    private void render() {
        list.removeAllViews();
        List<Forecast> visible = new ArrayList<>();
        for (Forecast f : items) if (showAll || !"OK".equals(f.getRisk()) || f.getPendingDemand() > 0) visible.add(f);
        if (visible.isEmpty()) {
            LinearLayout s = section("Résultat");
            text(s, "✅ Aucune rupture prévue sur 30 jours.", 14, false, R.color.vista_navy_muted);
            list.addView(detach(s));
            return;
        }
        for (Forecast f : visible) {
            LinearLayout card = section(riskLabel(f.getRisk()) + " · " + (f.getDaysLeft() < 0 ? "pas de rupture"
                    : f.getDaysLeft() == 0 ? "rupture maintenant" : "rupture J+" + f.getDaysLeft()));
            TextView name = text(card, f.getProductName(), 16, true, R.color.vista_navy);
            name.setTextColor(riskColor(f.getRisk()));
            text(card, f.getRationale(), 12, false, R.color.vista_navy_muted);
            if (f.getSuggestedOrder() > 0) {
                text(card, "À commander : " + f.getSuggestedOrder(), 14, true, R.color.vista_navy);
                if (controller.canEditStock()) {
                    MaterialButton b = new MaterialButton(this);
                    b.setText("🛒 Brouillon de commande");
                    b.setOnClickListener(v -> openDraft(f));
                    card.addView(b);
                }
            }
            list.addView(detach(card));
        }
    }

    /** section() ajoute à `content` : on déplace la carte (et son titre) dans `list`. */
    private android.view.View detach(LinearLayout inner) {
        android.view.View card = (android.view.View) inner.getParent();
        int idx = content.indexOfChild(card);
        android.view.View title = idx > 0 ? content.getChildAt(idx - 1) : null;
        content.removeView(card);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (title != null) { content.removeView(title); wrap.addView(title); }
        wrap.addView(card);
        return wrap;
    }

    private void openDraft(Forecast f) {
        AlertDialog loading = new AlertDialog.Builder(this).setMessage("🧠 Claude prépare la demande…").setCancelable(false).show();
        SharedBridge.draftOrder(this, f, d -> {
            loading.dismiss();
            showDraftDialog(this, controller, d, this::load);
        });
    }

    /** Dialogue brouillon de commande (réutilisé par le détail produit). */
    public static void showDraftDialog(Activity a, MainController controller, OrderDraft d, @Nullable Runnable onApproved) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView info = new TextView(a);
        info.setText(String.format(Locale.FRANCE,
                "Fournisseur habituel : %s\nDélai : %d j\nPrix unitaire : %.2f €\n\n%s\n%s",
                d.getSupplier() != null ? d.getSupplier() : "à définir", d.getLeadTimeDays(), d.getUnitPrice(),
                d.getByAI() ? "🧠 Justification rédigée par Claude :" : "Justification :", d.getJustification()));
        box.addView(info);
        EditText qty = new EditText(a);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        qty.setHint("Quantité");
        qty.setText(String.valueOf(d.getQuantity()));
        box.addView(qty);
        TextView note = new TextView(a);
        note.setTextSize(11);
        note.setText("La validation crée le bon de commande « En attente » (menu Commandes) et prévient l'équipe sur Slack.");
        box.addView(note);

        new AlertDialog.Builder(a)
                .setTitle("Brouillon : " + d.getProductName())
                .setView(box)
                .setPositiveButton("✅ Valider", (dlg, w) -> {
                    int q;
                    try { q = Integer.parseInt(qty.getText().toString().trim()); } catch (Exception e) { q = 0; }
                    final int fq = q;
                    controller.approveOrderDraft(d.getProductName(), q, d.getSupplier(), d.getJustification(), d.getByAI(), ref ->
                            new AlertDialog.Builder(a).setTitle("✅ Commande créée")
                                    .setMessage(d.getProductName() + " × " + fq + "\nRéférence : " + ref
                                            + "\nBon de commande ajouté dans « Commandes » (En attente).")
                                    .setPositiveButton("OK", (x, y) -> { if (onApproved != null) onApproved.run(); })
                                    .show());
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private static String riskLabel(String r) {
        return "CRITICAL".equals(r) ? "CRITIQUE" : "WARNING".equals(r) ? "À SURVEILLER" : "OK";
    }

    private int riskColor(String r) {
        return "CRITICAL".equals(r) ? getColor(R.color.vista_coral)
                : "WARNING".equals(r) ? getColor(R.color.vista_amber) : getColor(R.color.vista_navy);
    }
}
