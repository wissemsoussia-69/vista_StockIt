package com.example.stockit.util;

import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.example.stockit.R;

public final class KitAntiOubliDialog {

    private KitAntiOubliDialog() {}

    public interface OnKitValidated {
        void onValidated(boolean cablePower, boolean cableHdmi);
    }

    public static boolean requiresKit(String assetName) {
        if (assetName == null) return false;
        String n = assetName.trim().toLowerCase();
        return n.contains("screen") || n.contains("monitor");
    }

    public static void show(final Context ctx, final String assetName, final OnKitValidated cb) {
        final CheckBox cbPower = new CheckBox(ctx);
        cbPower.setText(R.string.txt_power_cable);

        final CheckBox cbHdmi = new CheckBox(ctx);
        cbHdmi.setText(R.string.txt_hdmi_cable);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (ctx.getResources().getDisplayMetrics().density * 20);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(cbPower);
        layout.addView(cbHdmi);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.dlg_title_kit_anti_forget, assetName))
                .setMessage(R.string.dlg_msg_kit_check)
                .setView(layout)
                .setCancelable(false)
                .setPositiveButton(R.string.action_validate, (d, w) -> {
                    if (cb != null) cb.onValidated(cbPower.isChecked(), cbHdmi.isChecked());
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> d.dismiss())
                .create();

        dialog.show();
    }
}
