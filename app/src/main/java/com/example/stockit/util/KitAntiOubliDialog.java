package com.example.stockit.util;

import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

/**
 * StockIT PFE — Kit Anti-Oubli.
 * Si l'objet identifié est un "Écran", on impose au technicien de cocher
 * les accessoires obligatoires avant de valider :
 *   [ ] Câble d'alimentation
 *   [ ] Câble HDMI
 */
public final class KitAntiOubliDialog {

    private KitAntiOubliDialog() {}

    public interface OnKitValidated {
        void onValidated(boolean cablePower, boolean cableHdmi);
    }

    /** @return true si l'asset déclenche le kit, false sinon. */
    public static boolean requiresKit(String assetName) {
        if (assetName == null) return false;
        String n = assetName.trim().toLowerCase();
        return n.contains("écran") || n.contains("ecran") || n.contains("monitor") || n.contains("moniteur");
    }

    public static void show(final Context ctx, final String assetName, final OnKitValidated cb) {
        final CheckBox cbPower = new CheckBox(ctx);
        cbPower.setText("Câble d'alimentation");

        final CheckBox cbHdmi = new CheckBox(ctx);
        cbHdmi.setText("Câble HDMI");

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (ctx.getResources().getDisplayMetrics().density * 20);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(cbPower);
        layout.addView(cbHdmi);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Kit Anti-Oubli — " + assetName)
                .setMessage("Cochez les dépendances effectivement présentes.\n"
                          + "• Kit COMPLET → ajout normal au stock.\n"
                          + "• Kit INCOMPLET → alerte Slack + ticket Jira automatique.")
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton("Valider", (d, w) -> {
                    if (cb != null) cb.onValidated(cbPower.isChecked(), cbHdmi.isChecked());
                })
                .setNegativeButton("Annuler", (d, w) -> d.dismiss())
                .create();

        dialog.show();
    }
}
