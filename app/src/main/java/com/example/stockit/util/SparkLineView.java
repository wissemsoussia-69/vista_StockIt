package com.example.stockit.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Sparkline (mini courbe) — remplace MPAndroidChart (JitPack retiré, TM-31) :
 * dessin Canvas maison de la ligne des derniers points, sans dépendance externe.
 */
public class SparkLineView extends View {

    private final List<Float> points = new ArrayList<>();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path fill = new Path();

    public SparkLineView(Context context) { this(context, null); }
    public SparkLineView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public SparkLineView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** Couleur de la ligne ; le remplissage est alpha 60. */
    public void setStrokeColor(int color) {
        linePaint.setColor(color);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
        fillPaint.setColor(color);
        fillPaint.setAlpha(60);
        fillPaint.setStyle(Paint.Style.FILL);
        invalidate();
    }

    /** Points à tracer (remp1acés à chaque appel). */
    public void setPoints(List<Float> values) {
        points.clear();
        if (values != null) points.addAll(values);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = points.size();
        if (n < 2) {
            canvas.drawColor(Color.TRANSPARENT);
            return;
        }
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : points) { min = Math.min(min, v); max = Math.max(max, v); }
        float range = max - min <= 0 ? 1 : max - min;
        float w = getWidth() - 8f, h = getHeight() - 8f;
        line.reset();
        fill.reset();
        for (int i = 0; i < n; i++) {
            float x = 4 + w * i / (n - 1);
            float y = 4 + h - h * (points.get(i) - min) / range;
            if (i == 0) { line.moveTo(x, y); fill.moveTo(x, 4 + h); fill.lineTo(x, y); }
            else { line.lineTo(x, y); fill.lineTo(x, y); }
        }
        fill.lineTo(4 + w, 4 + h);
        fill.close();
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);
    }
}
