package com.example.stockit.util;

import android.graphics.Color;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.stockit.R;
import com.google.android.material.snackbar.Snackbar;

/**
 * Fabrique de Snackbar aux couleurs Vista.
 *
 * Trois niveaux :
 *  - INFO    : fond navy, texte blanc, ripple bleu Vista
 *  - SUCCESS : fond mint, texte navy
 *  - URGENT  : fond corail, texte blanc
 *
 * Un léger haptique CONFIRM est déclenché à l'apparition pour renforcer
 * le feedback utilisateur (compatible >= API 26 via VIRTUAL_KEY).
 */
public final class VistaSnackbar {

    public enum Level { INFO, SUCCESS, URGENT }

    private VistaSnackbar() { /* utility */ }

    public static Snackbar make(View anchor, CharSequence message, Level level) {
        Snackbar sb = Snackbar.make(anchor, message, Snackbar.LENGTH_LONG);
        View v = sb.getView();
        v.setBackgroundColor(bgColor(anchor, level));

        // Coins arrondis via padding vertical + marge horizontale.
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int m = (int) (12 * v.getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        v.setLayoutParams(lp);
        v.setElevation(8f);

        TextView txt = v.findViewById(com.google.android.material.R.id.snackbar_text);
        if (txt != null) txt.setTextColor(textColor(anchor, level));

        anchor.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        return sb;
    }

    public static void show(View anchor, CharSequence message, Level level) {
        make(anchor, message, level).show();
    }

    private static int bgColor(View v, Level level) {
        switch (level) {
            case SUCCESS: return ContextCompat.getColor(v.getContext(), R.color.vista_mint);
            case URGENT:  return ContextCompat.getColor(v.getContext(), R.color.vista_coral);
            case INFO:
            default:      return ContextCompat.getColor(v.getContext(), R.color.vista_navy);
        }
    }

    private static int textColor(View v, Level level) {
        if (level == Level.SUCCESS) {
            return ContextCompat.getColor(v.getContext(), R.color.vista_navy);
        }
        return Color.WHITE;
    }
}
