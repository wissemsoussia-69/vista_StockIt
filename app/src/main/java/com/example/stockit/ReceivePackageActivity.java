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

import com.example.stockit.controller.MainController;
import com.example.stockit.controller.NotificationHelper;
import com.example.stockit.model.PurchaseOrder;
import com.example.stockit.util.DeliveryNoteParser;
import com.example.stockit.util.JiraClient;
import com.example.stockit.util.SlackNotifier;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

/**
 * StockIT PFE — Réception d'une facture / bon de livraison via capture photo + OCR IA.
 *
 * Dual-mode :
 *  A) MODE_CLASSIC (par défaut) : lancement depuis Dashboard → valide un PO en base (statut Reçu).
 *  B) MODE_SELECT_FOR_EQUIPMENT (Intent extra {@link #EXTRA_EQUIPMENT_NAME} présent) :
 *     lancement depuis ScanAssetActivity — la facture est parsée, la liste des POs est
 *     transmise à {@link POSelectionActivity} pour laisser l'utilisateur choisir.
 *     Renvoie via setResult() le PO choisi (numéro, description, fournisseur).
 */
public class ReceivePackageActivity extends AppCompatActivity {

    private static final String TAG = "ReceivePackage";
    private static final int REQ_CAMERA = 5151;
    private static final int REQ_PO_SELECT = 5152;

    /** Si présent, active le mode "sélection pour équipement" (renvoie via setResult). */
    public static final String EXTRA_EQUIPMENT_NAME = "equipment_name";

    private PreviewView preview;
    private TextView    status;
    private ProgressBar progress;
    private Button      btnCapture;

    private ImageCapture imageCapture;
    private MainController controller;

    /** Nom de l'équipement passé par l'appelant (mode B). null en mode A. */
    private String equipmentName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive_package);

        preview   = findViewById(R.id.rpPreview);
        status    = findViewById(R.id.rpStatus);
        progress  = findViewById(R.id.rpProgress);
        btnCapture= findViewById(R.id.rpBtnCapture);
        Button btnCancel = findViewById(R.id.rpBtnCancel);

        controller = new MainController(this);
        equipmentName = getIntent().getStringExtra(EXTRA_EQUIPMENT_NAME);

        if (equipmentName != null) {
            status.setText("📄 Facture pour \"" + equipmentName + "\" — cadre puis capture.");
        } else {
            status.setText("📄 Cadre le bon de livraison puis capture.");
        }

        btnCapture.setOnClickListener(v -> capture());
        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

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
                        if (bmp == null) {
                            setBusy(false, "❌ Décodage image échoué"); return;
                        }
                        parse(bmp);
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        setBusy(false, "❌ Capture : " + e.getMessage());
                    }
                });
    }

    private void parse(Bitmap bmp) {
        setBusy(true, "🧠 [1/3] OCR MLKit local en cours…");
        try {
            DeliveryNoteParser.parse(bmp,
                    note -> runOnUiThread(() -> {
                        try {
                            setBusy(false, null);
                            if (note == null) { showError("Callback null (parser silencieux)"); return; }
                            if (note.error != null) {
                                String extra = note.rawOcr != null && !note.rawOcr.isEmpty()
                                        ? "\n\n— Texte OCR extrait —\n" + trim(note.rawOcr, 600)
                                        : "";
                                showError("Extraction IA impossible.\nErreur : " + note.error + extra);
                                return;
                            }
                            Log.i(TAG, "Extraction OK — supplier=" + note.supplier
                                    + " purchaseOrders=" + note.purchaseOrders.size());
                            if (equipmentName != null) {
                                launchPOSelection(note);
                            } else {
                                showClassicResult(note);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Post-parse crash", t);
                            showError("Crash post-parse : " + t.getClass().getSimpleName() + " — " + t.getMessage());
                        }
                    }),
                    step -> runOnUiThread(() -> setBusy(true, "⏳ " + step))
            );
        } catch (Throwable t) {
            Log.e(TAG, "parse() crash", t);
            setBusy(false, null);
            showError("Crash parse() : " + t.getClass().getSimpleName() + " — " + t.getMessage());
        }
    }

    // ---------- MODE B : sélection pour équipement ----------
    private void launchPOSelection(DeliveryNoteParser.ParsedNote note) {
        Intent i = new Intent(this, POSelectionActivity.class);
        i.putExtra(POSelectionActivity.EXTRA_EQUIPMENT_NAME, equipmentName);
        i.putExtra(POSelectionActivity.EXTRA_PARSED_NOTE, note);
        startActivityForResult(i, REQ_PO_SELECT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PO_SELECT) return;
        // On propage le résultat au parent (ScanAssetActivity)
        setResult(resultCode, data);
        finish();
    }

    // ---------- MODE A : validation d'un PO en base ----------
    private void showClassicResult(final DeliveryNoteParser.ParsedNote note) {
        if (note.purchaseOrders.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("❌ Aucun PO détecté")
                    .setMessage("La facture ne contient pas de numéro de PO exploitable.\n\n"
                            + "— OCR —\n" + trim(note.rawOcr, 500))
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        // On matche chaque PO extrait contre la base et affiche un résumé
        controller.getPurchaseOrders(orders -> {
            StringBuilder msg = new StringBuilder();
            msg.append("Fournisseur : ").append(note.supplier == null ? "?" : note.supplier).append("\n\n");
            msg.append(note.purchaseOrders.size()).append(" PO détectés :\n\n");
            PurchaseOrder firstMatch = null;
            for (DeliveryNoteParser.POBlock po : note.purchaseOrders) {
                PurchaseOrder matched = matchDb(orders, po);
                msg.append("• ").append(po.number)
                   .append(matched != null ? " ✅ en base" : " ❓ inconnu")
                   .append("\n    ").append(po.description == null ? "(pas de desc)" : po.description).append("\n");
                if (firstMatch == null) firstMatch = matched;
            }
            final PurchaseOrder toValidate = firstMatch;
            AlertDialog.Builder b = new AlertDialog.Builder(this)
                    .setTitle("📄 Résultat facture")
                    .setMessage(msg.toString());
            if (toValidate != null && !"Reçu".equalsIgnoreCase(toValidate.getStatus())) {
                b.setPositiveButton("✅ Valider PO#" + toValidate.getId(), (d, w) -> validate(toValidate, note));
            }
            b.setNegativeButton("Fermer", null);
            b.show();
        });
    }

    private PurchaseOrder matchDb(java.util.List<PurchaseOrder> orders, DeliveryNoteParser.POBlock po) {
        if (orders == null) return null;
        Integer id = DeliveryNoteParser.extractIntFromPoNumber(po.number);
        if (id != null) {
            for (PurchaseOrder o : orders) if (o.getId() == id) return o;
        }
        return null;
    }

    private void validate(final PurchaseOrder po, final DeliveryNoteParser.ParsedNote note) {
        po.setStatus("Reçu");
        controller.updatePurchaseOrder(po, () -> runOnUiThread(() -> {
            String label = "PO#" + po.getId() + " — " + po.getProductName() + " x" + po.getQuantity();
            SlackNotifier.send(":white_check_mark: [StockIT] Reception validee par OCR : " + label
                    + " (fournisseur " + po.getSupplier() + ")");
            NotificationHelper.showNotification(this, "StockIT — Reception validee",
                    label, (int) System.currentTimeMillis());
            com.example.stockit.util.StockItReporter.sendEvent(this,
                    "Réception PO#" + po.getId() + " validée",
                    "📦 Un bon de commande vient d'être réceptionné et validé par OCR mobile.\n\n"
                            + "• PO          : #" + po.getId() + "\n"
                            + "• Produit     : " + po.getProductName() + "\n"
                            + "• Quantité    : " + po.getQuantity() + "\n"
                            + "• Fournisseur : " + po.getSupplier() + "\n\n"
                            + "Statut du PO passé à \"Reçu\".",
                    "wissem.soussia@vista.com");
            JiraClient.createTask(
                    com.example.stockit.BuildConfig.JIRA_PROJECT_KEY,
                    "[StockIT] Reception PO#" + po.getId() + " - " + po.getProductName(),
                    "Colis recu et valide via OCR mobile.\nFournisseur DB: " + po.getSupplier()
                            + ", quantite: " + po.getQuantity()
                            + ".\nSupplier facture: " + note.supplier
                            + ".\n" + note.purchaseOrders.size() + " PO detecte(s) au total.",
                    (ok, res) -> Log.i(TAG, "Jira log ticket -> ok=" + ok + " res=" + res));

            new AlertDialog.Builder(this)
                    .setTitle("🟢 Réception enregistrée")
                    .setMessage(label + "\n\nStatut passé à \"Reçu\".")
                    .setPositiveButton("OK", (d, w) -> { d.dismiss(); finish(); })
                    .show();
        }));
    }

    private void showError(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Réception colis")
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
