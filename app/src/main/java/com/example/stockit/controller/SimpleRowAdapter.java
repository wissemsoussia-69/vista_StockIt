package com.example.stockit.controller;

import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.R;

import java.util.List;

/**
 * Adapter générique « titre / sous-titre / valeur à droite » (classement, prévisions,
 * qualité IA, historique…). Construit ses vues par code : aucun layout dédié requis.
 */
public class SimpleRowAdapter extends RecyclerView.Adapter<SimpleRowAdapter.VH> {

    public static class Row {
        public final String title, subtitle, trailing;
        public final int trailingColor;
        public final Object tag;
        public Row(String title, String subtitle, String trailing, int trailingColor, Object tag) {
            this.title = title; this.subtitle = subtitle; this.trailing = trailing;
            this.trailingColor = trailingColor; this.tag = tag;
        }
    }

    public interface OnRowClick { void onClick(Row row); }

    private final List<Row> rows;
    private final OnRowClick click;

    public SimpleRowAdapter(List<Row> rows, OnRowClick click) {
        this.rows = rows;
        this.click = click;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        android.content.Context c = parent.getContext();
        int pad = dp(c, 14);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(c, 6));
        row.setLayoutParams(lp);
        row.setBackgroundColor(ContextCompat.getColor(c, R.color.vista_surface));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(ContextCompat.getColor(c, R.color.vista_navy));
        TextView sub = new TextView(c);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(ContextCompat.getColor(c, R.color.vista_navy_muted));
        texts.addView(title);
        texts.addView(sub);

        TextView trailing = new TextView(c);
        trailing.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        trailing.setTypeface(Typeface.DEFAULT_BOLD);
        trailing.setPadding(dp(c, 8), 0, 0, 0);

        row.addView(texts);
        row.addView(trailing);
        return new VH(row, title, sub, trailing);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Row r = rows.get(position);
        h.title.setText(r.title);
        h.sub.setText(r.subtitle == null ? "" : r.subtitle);
        h.sub.setVisibility(r.subtitle == null || r.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        h.trailing.setText(r.trailing == null ? "" : r.trailing);
        h.trailing.setTextColor(r.trailingColor != 0 ? r.trailingColor
                : ContextCompat.getColor(h.itemView.getContext(), R.color.vista_blue));
        h.itemView.setOnClickListener(click == null ? null : v -> click.onClick(r));
    }

    @Override public int getItemCount() { return rows.size(); }

    static int dp(android.content.Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, sub, trailing;
        VH(View v, TextView t, TextView s, TextView tr) { super(v); title = t; sub = s; trailing = tr; }
    }
}
