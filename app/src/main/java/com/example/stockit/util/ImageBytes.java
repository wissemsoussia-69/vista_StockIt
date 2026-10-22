package com.example.stockit.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Lecture d'une image (galerie) → JPEG redimensionné (≤ 1600 px) pour l'IA. */
public final class ImageBytes {
    private ImageBytes() {}

    @Nullable
    public static byte[] readJpeg(Context ctx, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1600) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
            if (bmp == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static Bitmap readBitmap(Context ctx, Uri uri) {
        byte[] b = readJpeg(ctx, uri);
        return b == null ? null : BitmapFactory.decodeByteArray(b, 0, b.length);
    }
}
