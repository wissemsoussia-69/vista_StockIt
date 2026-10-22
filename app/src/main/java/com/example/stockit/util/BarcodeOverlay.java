package com.example.stockit.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class BarcodeOverlay extends View {
    private final Paint paint = new Paint();
    private List<Rect> rects = new ArrayList<>();

    public BarcodeOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8.0f);
    }

    public void updateRects(List<Rect> newRects) {
        this.rects = newRects;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Rect rect : rects) {
            canvas.drawRect(rect, paint);
        }
    }
}
