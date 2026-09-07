package com.example.stockit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.stockit.util.PackageLabelParser;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

/**
 * StockIT PFE — Scan de l'étiquette du carton.
 *
 * Entrée (Intent extras, optionnels) :
 *   - EXTRA_EXPECTED_PO (String) : le PO déjà sélectionné via la facture (pour cross-check).
 *
 * Sortie (setResult) :
 *   - EXTRA_PRODUCT_NAME    (String)
 *   - EXTRA_QUANTITY        (int)
 *   - EXTRA_ARTICLE_NUMBER  (String)
 *   - EXTRA_BRAND           (String)
 *   - EXTRA_PO_ON_LABEL     (String)
 */
public class PackageLabelActivity extends AppCompatActivity {

    private static final String TAG = "PackageLabel";
    private static final int REQ_CAMERA = 6161;

    public static final String EXTRA_EXPECTED_PO      = "expected_po";
    public static final String EXTRA_PRODUCT_NAME     = "product_name";
    public static final String EXTRA_QUANTITY         = "quantity";
    public static final String EXTRA_ARTICLE_NUMBER   = "article_number";
    public static final String EXTRA_BRAND            = "brand";
    public static final String EXTRA_PO_ON_LABEL      = "po_on_label";

    private PreviewView preview;
    private TextView    status;
    private ProgressBar progress;
    private Button      btnCapture;
    private ImageCapture imageCapture;

    private String expectedPo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_package_label);

        preview   = findViewById(R.id.plPreview);
        status    = findViewById(R.id.plStatus);
        progress  = findViewById(R.id.plProgress);
        btnCapture= findViewById(R.id.plBtnCapture);
        Button btnCancel = findViewById(R.id.plBtnCancel);

        expectedPo = getIntent().getStringExtra(EXTRA_EXPECTED_PO);
        if (expectedPo != null) {
            status.setText("🏷️ Étiquette du carton (PO attendu : " + expectedPo + ")");
        }

        btnCapture.setOnClickListener(v -> capture());
        btnCancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ_CAMERA && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Toast.makeText(this, "Permission caméra refusée", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();
                CameraSelector sel = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                provider.unbindAll();
                provider.bindToLifecycle(this, sel, p, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Erreur caméra : " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capture() {
        if (imageCapture == null) return;
        setBusy(true, "📸 Capture…");
        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                        Bitmap bmp = toBitmap(image);
                        image.close();
                        if (bmp == null) { setBusy(false, "❌ Décodage image échoué"); return; }
                        parse(bmp);
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        setBusy(false, "❌ Capture : " + e.getMessage());
                    }
                });
    }

    private void parse(Bitmap bmp) {
        setBusy(true, "🧠 OCR MLKit sur l'étiquette…");
        try {
            PackageLabelParser.parse(bmp,
                    lbl -> runOnUiThread(() -> {
                        try {
                            setBusy(false, null);
                            if (lbl == null) { showError("Callback null"); return; }
                            if (lbl.error != null) {
                                String extra = lbl.rawOcr != null && !lbl.rawOcr.isEmpty()
                                        ? "\n\n— OCR extrait —\n" + trim(lbl.rawOcr, 500) : "";
                                showError("Extraction IA impossible.\nErreur : " + lbl.error + extra);
                                return;
                            }
                            confirmAndReturn(lbl);
                        } catch (Throwable t) {
                            Log.e(TAG, "post-parse crash", t);
                            showError("Crash post-parse : " + t.getClass().getSimpleName() + " — " + t.getMessage());
                        }
                    }),
                    step -> runOnUiThread(() -> setBusy(true, "⏳ " + step))
            );
        } catch (Throwable t) {
            Log.e(TAG, "parse crash", t);
            setBusy(false, null);
            showError("Crash parse : " + t.getMessage());
        }
    }

    private void confirmAndReturn(final PackageLabelParser.Label lbl) {
        // Récap dialog
        StringBuilder msg = new StringBuilder();
        msg.append("• Produit : ").append(lbl.productName == null ? "?" : lbl.productName).append("\n");
        msg.append("• Quantité : ").append(lbl.quantity).append("\n");
        if (lbl.brand != null)         msg.append("• Marque : ").append(lbl.brand).append("\n");
        if (lbl.articleNumber != null) msg.append("• Art.-No. : ").append(lbl.articleNumber).append("\n");
        if (lbl.poOnLabel != null)     msg.append("• PO étiquette : ").append(lbl.poOnLabel).append("\n");

        boolean mismatch = expectedPo != null && lbl.poOnLabel != null
                && !normalize(expectedPo).equals(normalize(lbl.poOnLabel))
                && !normalize(expectedPo).contains(normalize(lbl.poOnLabel))
                && !normalize(lbl.poOnLabel).contains(normalize(expectedPo));

        if (mismatch) {
            new AlertDialog.Builder(this)
                    .setTitle("⚠️ PO facture ≠ PO étiquette")
                    .setMessage(msg + "\n\nAttendu (facture) : " + expectedPo
                            + "\nDétecté (étiquette) : " + lbl.poOnLabel
                            + "\n\nContinuer quand même ?")
                    .setPositiveButton("Oui, valider", (d, w) -> returnResult(lbl))
                    .setNegativeButton("Non, re-scanner", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("🏷️ Étiquette lue")
                    .setMessage(msg)
                    .setPositiveButton("✅ Utiliser", (d, w) -> returnResult(lbl))
                    .setNegativeButton("Re-scanner", null)
                    .show();
        }
    }

    private String normalize(String s) {
        if (s == null) return "";
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) d.append(Character.toLowerCase(s.charAt(i)));
        return d.toString();
    }

    private void returnResult(@NonNull PackageLabelParser.Label lbl) {
        Intent data = new Intent();
        data.putExtra(EXTRA_PRODUCT_NAME,   lbl.productName);
        data.putExtra(EXTRA_QUANTITY,       lbl.quantity);
        data.putExtra(EXTRA_ARTICLE_NUMBER, lbl.articleNumber);
        data.putExtra(EXTRA_BRAND,          lbl.brand);
        data.putExtra(EXTRA_PO_ON_LABEL,    lbl.poOnLabel);
        setResult(RESULT_OK, data);
        finish();
    }

    private void showError(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Étiquette carton")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private void setBusy(boolean busy, String label) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnCapture.setEnabled(!busy);
        if (label != null) status.setText(label);
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static Bitmap toBitmap(ImageProxy image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            int rot = image.getImageInfo().getRotationDegrees();
            if (bmp != null && rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "toBitmap", e);
            return null;
        }
    }
}
