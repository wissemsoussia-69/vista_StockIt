package com.example.stockit;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

/**
 * Base des écrans « formulaire » (Paramètres, Qualité IA…) construits par code
 * dans le style Vista : toolbar + sections en cartes.
 */
public abstract class SimpleScreenActivity extends AppCompatActivity {

    protected LinearLayout content;

    protected abstract String screenTitle();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.vista_bg));
        root.setFitsSystemWindows(true);

        Toolbar tb = new Toolbar(this);
        tb.setBackgroundColor(ContextCompat.getColor(this, R.color.vista_navy));
        tb.setTitleTextColor(0xFFFFFFFF);
        tb.setTitle(L(screenTitle()));
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (tb.getNavigationIcon() != null) tb.getNavigationIcon().setTint(0xFFFFFFFF);

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /** Texte traduit dans la langue de l'app (mêmes clés que l'app iOS). */
    protected static String L(String key, Object... args) {
        return com.example.stockit.util.AppLanguage.L(key, args);
    }

    protected int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Nouvelle section (titre + carte) ; retourne le conteneur de la carte. */
    protected LinearLayout section(String title) {
        TextView t = new TextView(this);
        t.setText(L(title).toUpperCase(com.example.stockit.util.AppLanguage.locale()));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ContextCompat.getColor(this, R.color.vista_navy_muted));
        t.setPadding(dp(4), dp(16), 0, dp(6));
        content.addView(t);

        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(12));
        card.setCardElevation(dp(1));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.addView(inner);
        content.addView(card);
        return inner;
    }

    protected TextView text(LinearLayout parent, String s, int sp, boolean bold, int colorRes) {
        TextView t = new TextView(this);
        t.setText(L(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(ContextCompat.getColor(this, colorRes));
        t.setPadding(0, dp(4), 0, dp(4));
        parent.addView(t);
        return t;
    }

    /** Ligne « libellé ……… valeur ». */
    protected TextView row(LinearLayout parent, String label, String value, int valueColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(6), 0, dp(6));
        TextView l = new TextView(this);
        l.setText(L(label));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        l.setTextColor(ContextCompat.getColor(this, R.color.vista_navy));
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(L(value));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(valueColor);
        r.addView(v);
        parent.addView(r);
        return v;
    }

    protected static int okColor(Context c, boolean ok) {
        return ok ? 0xFF2E9E6B : ContextCompat.getColor(c, R.color.vista_amber);
    }

    protected View divider(LinearLayout parent) {
        View d = new View(this);
        d.setBackgroundColor(0x110B1F44);
        parent.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return d;
    }
}
