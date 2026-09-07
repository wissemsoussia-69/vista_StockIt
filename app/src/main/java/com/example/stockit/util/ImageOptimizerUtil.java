package com.example.stockit.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class ImageOptimizerUtil {

    public interface OptimizationCallback {
        void onOptimized(File optimizedFile, long originalSize, long newSize);
    }

    public static void optimizeImage(Context context, Uri uri, OptimizationCallback callback) {
        try {
            InputStream isSize = context.getContentResolver().openInputStream(uri);
            long originalSize = isSize.available();
            isSize.close();

            InputStream is = context.getContentResolver().openInputStream(uri);
            Bitmap originalBitmap = BitmapFactory.decodeStream(is);
            is.close();

            ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableMultipleObjects()
                    .enableClassification()
                    .build();
            ObjectDetector objectDetector = ObjectDetection.getClient(options);
            InputImage image = InputImage.fromBitmap(originalBitmap, 0);

            objectDetector.process(image)
                    .addOnSuccessListener(objects -> {
                        Bitmap processedBitmap;
                        if (!objects.isEmpty()) {
                            Rect bounds = objects.get(0).getBoundingBox();
                            
                            int left = Math.max(0, bounds.left);
                            int top = Math.max(0, bounds.top);
                            int width = Math.min(originalBitmap.getWidth() - left, bounds.width());
                            int height = Math.min(originalBitmap.getHeight() - top, bounds.height());

                            processedBitmap = Bitmap.createBitmap(originalBitmap, left, top, width, height);
                        } else {
                            processedBitmap = originalBitmap;
                        }

                        File optimizedFile = new File(context.getExternalFilesDir(null), "OPT_" + System.currentTimeMillis() + ".webp");
                        try (FileOutputStream out = new FileOutputStream(optimizedFile)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                processedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out);
                            } else {
                                processedBitmap.compress(Bitmap.CompressFormat.WEBP, 75, out);
                            }
                            
                            long newSize = optimizedFile.length();
                            callback.onOptimized(optimizedFile, originalSize, newSize);
                        } catch (Exception e) {
                            Log.e("Optimizer", "Error saving webp", e);
                        }
                    });

        } catch (Exception e) {
            Log.e("Optimizer", "Optimization failed", e);
        }
    }
}
