package com.example.stockit;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.example.stockit.util.BarcodeOverlay;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class BatchScanActivity extends AppCompatActivity {

    private PreviewView viewFinder;
    private BarcodeOverlay barcodeOverlay;
    private TextView txtCount;
    private final Set<String> detectedCodes = new HashSet<>();
    private BarcodeScanner scanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_scan);

        viewFinder = findViewById(R.id.viewFinder);
        barcodeOverlay = findViewById(R.id.barcodeOverlay);
        txtCount = findViewById(R.id.txtCount);
        Button btnFinish = findViewById(R.id.btnFinishScan);

        scanner = BarcodeScanning.getClient();

        startCamera();

        btnFinish.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putStringArrayListExtra("codes", new ArrayList<>(detectedCodes));
            setResult(RESULT_OK, data);
            finish();
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Erreur Caméra: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), imageProxy -> {
            @SuppressWarnings("UnsafeOptInUsageError")
            InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
            
            scanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        List<Rect> rects = new ArrayList<>();
                        for (Barcode barcode : barcodes) {
                            rects.add(barcode.getBoundingBox());
                            if (barcode.getRawValue() != null) {
                                detectedCodes.add(barcode.getRawValue());
                            }
                        }
                        barcodeOverlay.updateRects(rects);
                        txtCount.setText("Codes détectés : " + detectedCodes.size());
                    })
                    .addOnCompleteListener(task -> imageProxy.close());
        });

        preview.setSurfaceProvider(viewFinder.getSurfaceProvider());
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }
}
