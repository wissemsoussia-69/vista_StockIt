package com.example.stockit.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Camembert de répartition par catégorie — remplace MPAndroidChart (JitPack retiré,
 * TM-31) : dessin Canvas maison, sans dépendance externe, même information.
 */
public class CategoryPieView extends View {

    /** Secteur : libellé + quantité. */
    public static class Slice {
        public final String label;
        public final float value;
        public final int color;
        public Slice(String label, float value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    /** Palette publique (utilisée par les appelants pour colorer les secteurs). */
    public static final int[] PALETTE = {
            0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFF7B1FA2,
            0xFF0097A7, 0xFFD32F2F, 0xFF5D4037, 0xFFC2185B,
    };

    private final List<Slice> slices = new ArrayList<>();
    private final Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    public CategoryPieView(Context context) { this(context, null); }
    public CategoryPieView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public CategoryPieView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        textPaint.setColor(0xFF333333);
        textPaint.setTextSize(34);
    }

    /** Remplace les données du camembert. */
    public void setSlices(List<Slice> data) {
        slices.clear();
        if (data != null) slices.addAll(data);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (slices.isEmpty()) return;
        float total = 0;
        for (Slice s : slices) total += s.value;
        if (total <= 0) return;

        float legendWidth = slices.size() > 6 ? 0.38f : 0.30f;
        float d = Math.min(getWidth() * (1 - legendWidth) - 24, getHeight() - 24);
        float left = 12, top = (getHeight() - d) / 2f;
        oval.set(left, top, left + d, top + d);

        float start = -90f;
        for (Slice s : slices) {
            float sweep = s.value / total * 360f;
            slicePaint.setColor(s.color);
            canvas.drawArc(oval, start, sweep, true, slicePaint);
            start += sweep;
        }

        float tx = left + d + 16;
        float ty = top + textPaint.getTextSize();
        for (Slice s : slices) {
            slicePaint.setColor(s.color);
            canvas.drawRect(tx, ty - textPaint.getTextSize(), tx + 22, ty - 6, slicePaint);
            textPaint.setColor(0xFF333333);
            canvas.drawText(s.label + " — " + (int) s.value, tx + 30, ty - 8, textPaint);
            ty += textPaint.getTextSize() * 1.5f;
        }
    }
}
