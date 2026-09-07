package com.example.stockit;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
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
import com.example.stockit.util.GeminiGatewayClient;
import com.example.stockit.util.JiraClient;
import com.example.stockit.util.KitAntiOubliDialog;
import com.example.stockit.util.SlackNotifier;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

/**
 * StockIT PFE — Scan d'un asset informatique.
 *
 * Flux :
 * 1. Aperçu CameraX (PreviewView).
 * 2. Bouton "Capturer" -> photo JPEG.
 * 3. Envoi à la passerelle Gemini (GeminiGatewayClient) avec un prompt strict.
 * 4. Résultat nettoyé (.trim()) affiché dans un TextView.
 * 5. Si "Écran" -> KitAntiOubliDialog obligatoire avant validation en base.
 * 6. Slack + Jira notifiés en cas d'anomalie ou de kit incomplet.
 */
public class ScanAssetActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 4242;

    private PreviewView previewView;
    private TextView txtResult;
    private ProgressBar progress;
    private Button btnCapture;
    private ImageCapture imageCapture;
    private MainController controller;

    // --- Vista visual layer ---
    private View scanBeam;
    private View scanTargetFrame;
    private LinearLayout scanMatchBadge;
    private TextView scanMatchText;
    private View scanWaveRing1;
    private View scanWaveRing2;
    private View scanGrid;
    private TextView scanZoomChip;
    private ImageButton scanTorch;
    private Camera camera;
    private ScaleGestureDetector scaleDetector;
    private boolean torchOn = false;
    private android.animation.ObjectAnimator beamAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_asset);

        previewView = findViewById(R.id.scanPreview);
        txtResult   = findViewById(R.id.scanResult);
        progress    = findViewById(R.id.scanProgress);
        btnCapture  = findViewById(R.id.scanCapture);
        controller  = new MainController(this);

        // Overlay Vista : faisceau bleu + cadre de ciblage + badge Match
        scanBeam        = findViewById(R.id.scanBeam);
        scanTargetFrame = findViewById(R.id.scanTargetFrame);
        scanMatchBadge  = findViewById(R.id.scanMatchBadge);
        scanMatchText   = findViewById(R.id.scanMatchText);
        scanWaveRing1   = findViewById(R.id.scanWaveRing1);
        scanWaveRing2   = findViewById(R.id.scanWaveRing2);
        scanTorch       = findViewById(R.id.scanTorch);
        scanGrid        = findViewById(R.id.scanGrid);
        scanZoomChip    = findViewById(R.id.scanZoomChip);

        btnCapture.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            capture();
        });

        if (scanTorch != null) scanTorch.setOnClickListener(v -> toggleTorch());

        setupPinchZoom();

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
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

                provider.unbindAll();
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture);
                // Torche : masque le bouton si le device n'a pas de flash.
                if (scanTorch != null && camera != null
                        && !camera.getCameraInfo().hasFlashUnit()) {
                    scanTorch.setVisibility(View.GONE);
                }
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Erreur caméra : " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capture() {
        if (imageCapture == null) return;
        setBusy(true, "Capture…");
        hideMatchBadge();
        hideTargetFrame();
        startScanBeam();
        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                        byte[] jpeg = toJpegBytes(image);
                        image.close();
                        analyze(jpeg);
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        setBusy(false, null);
                        stopScanBeam();
                        Toast.makeText(ScanAssetActivity.this, "Capture échouée", Toast.LENGTH_SHORT).show();
                        SlackNotifier.send(":warning: [StockIT] Capture caméra échouée : " + e.getMessage());
                        com.example.stockit.util.StockItReporter.sendEvent(ScanAssetActivity.this,
                                "Incident caméra (scan équipement)",
                                "⚠️ La capture caméra a échoué pendant un scan équipement.\n\n"
                                        + "• Détail technique : " + e.getMessage() + "\n"
                                        + "• Écran            : ScanAssetActivity\n\n"
                                        + "👉 Vérifier permissions caméra et état du terminal.",
                                "wissem.soussia@vista.com");
                    }
                });
    }

    private void analyze(byte[] jpeg) {
        setBusy(true, "Analyse IA (Gemini)…");
        GeminiGatewayClient.identify(jpeg, (name, error) ->
                runOnUiThread(() -> {
                    setBusy(false, null);
                    stopScanBeam();
                    if (error != null || name == null || name.isEmpty()) {
                        String err = error != null ? error : "réponse vide";
                        txtResult.setText("❌ Analyse impossible (" + err + ")");
                        SlackNotifier.send(":x: [StockIT] Anomalie scan IA : " + err);
                        com.example.stockit.util.StockItReporter.sendEvent(ScanAssetActivity.this,
                                "Anomalie détection IA Gemini",
                                "🤖 L'IA n'a pas pu identifier l'objet scanné.\n\n"
                                        + "• Erreur renvoyée : " + err + "\n\n"
                                        + "Cas possibles : image floue, cadrage incorrect, limite du modèle.\n"
                                        + "À conserver pour ajustement du prompt.",
                                "wissem.soussia@vista.com");
                        return;
                    }
                    txtResult.setText("🧠 Objet détecté : " + name);
                    // Effet Vista : cadre de ciblage bleu marine + badge Match vert menthe.
                    showTargetFrame();
                    showMatchSuccess("Match : " + name);
                    handleDetected(name);
                }));
    }

    private void handleDetected(final String detectedName) {
        if (KitAntiOubliDialog.requiresKit(detectedName)) {
            KitAntiOubliDialog.show(this, detectedName, (power, hdmi) -> {
                if (power && hdmi) {
                    askScanFacture(detectedName, "Kit complet (Alim + HDMI)");
                } else {
                    txtResult.setText("⛔ Kit incomplet — validation refusée.");
                    SlackNotifier.send(":rotating_light: [StockIT] Kit anti-oubli incomplet pour un Écran (Alim=" + power + ", HDMI=" + hdmi + ")");
                    com.example.stockit.util.StockItReporter.sendEvent(this,
                            "Kit anti-oubli incomplet (écran)",
                            "⛔ Un écran a été scanné sans tous les accessoires obligatoires.\n\n"
                                    + "• Alimentation présente : " + power + "\n"
                                    + "• Câble HDMI présent    : " + hdmi + "\n\n"
                                    + "Un ticket Jira a été créé automatiquement pour tracer l'incident.",
                            "wissem.soussia@vista.com");
                    JiraClient.createTask(
                            BuildConfig.JIRA_PROJECT_KEY,
                            "[StockIT] Kit accessoires manquant pour un ecran",
                            "Un technicien a scanne un ecran sans confirmer toutes les dependances (Alim=" + power + ", HDMI=" + hdmi + ").",
                            (ok, res) -> runOnUiThread(() -> {
                                if (ok) {
                                    String key = res;
                                    txtResult.setText("⛔ Kit incomplet — Ticket Jira créé : " + key);
                                    new androidx.appcompat.app.AlertDialog.Builder(this)
                                            .setTitle("🟢 Ticket Jira créé")
                                            .setMessage("Clé : " + key + "\n\nProjet : " + BuildConfig.JIRA_PROJECT_KEY
                                                    + "\nURL : " + BuildConfig.JIRA_BASE_URL + "/browse/" + key)
                                            .setPositiveButton("OK", null)
                                            .show();
                                } else {
                                    txtResult.setText("⛔ Jira KO — " + res);
                                    SlackNotifier.send(":warning: [StockIT] Echec creation Jira : " + res);
                                    new androidx.appcompat.app.AlertDialog.Builder(this)
                                            .setTitle("🔴 Jira KO — projet " + BuildConfig.JIRA_PROJECT_KEY)
                                            .setMessage("Détail brut renvoyé par Jira :\n\n" + res)
                                            .setPositiveButton("OK", null)
                                            .show();
                                }
                            }));
                }
            });
        } else {
            askScanFacture(detectedName, "Scan IA StockIT PFE");
        }
    }

    // --- StockIT PFE : chaînage équipement → facture ---

    private static final int REQ_FACTURE_SCAN = 7070;

    /** Nom + motif de l'équipement en attente de sauvegarde (mémoire, pas encore inséré en DB). */
    private String pendingName;
    private String pendingReason;

    private void askScanFacture(final String name, final String reason) {
        pendingName = name;
        pendingReason = reason;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("📄 Scanner la facture liée ?")
                .setMessage("Équipement : " + name + "\n\n"
                        + "Prends en photo la facture pour rattacher automatiquement le PO. "
                        + "Sinon l'équipement sera ajouté au stock sans PO.")
                .setPositiveButton("📷 Scanner la facture", (d, w) -> launchFactureScan(name))
                .setNegativeButton("Enregistrer sans PO", (d, w) -> saveAsset(name, reason, null, null, null))
                .setCancelable(false)
                .show();
    }

    private void launchFactureScan(String name) {
        Intent i = new Intent(this, ReceivePackageActivity.class);
        i.putExtra(ReceivePackageActivity.EXTRA_EQUIPMENT_NAME, name);
        startActivityForResult(i, REQ_FACTURE_SCAN);
    }

    // --- StockIT PFE : chaînage facture → étiquette carton ---

    private static final int REQ_LABEL_SCAN = 7071;

    /** État intermédiaire entre la facture et l'étiquette. */
    private String pendingPoNumber;
    private String pendingPoDescription;
    private String pendingSupplier;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FACTURE_SCAN) {
            if (pendingName == null) return;
            String poNumber = null, poDesc = null, supplier = null;
            if (resultCode == RESULT_OK && data != null) {
                poNumber = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_NUMBER);
                poDesc   = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_DESCRIPTION);
                supplier = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_SUPPLIER);
            }
            askScanLabel(poNumber, poDesc, supplier);
            return;
        }

        if (requestCode == REQ_LABEL_SCAN) {
            if (pendingName == null) return;
            String name = pendingName, reason = pendingReason;
            String poNumber = pendingPoNumber, poDesc = pendingPoDescription, supplier = pendingSupplier;
            pendingName = null; pendingReason = null;
            pendingPoNumber = null; pendingPoDescription = null; pendingSupplier = null;

            if (resultCode == RESULT_OK && data != null) {
                String prodName    = data.getStringExtra(PackageLabelActivity.EXTRA_PRODUCT_NAME);
                int    qty         = data.getIntExtra(PackageLabelActivity.EXTRA_QUANTITY, 1);
                String articleNum  = data.getStringExtra(PackageLabelActivity.EXTRA_ARTICLE_NUMBER);
                String brand       = data.getStringExtra(PackageLabelActivity.EXTRA_BRAND);
                String poOnLabel   = data.getStringExtra(PackageLabelActivity.EXTRA_PO_ON_LABEL);

                // Nom précis prioritaire, sinon nom générique Claude
                String finalName = (prodName != null && !prodName.isEmpty()) ? prodName : name;
                // Description enrichie
                String finalDesc = (brand != null ? brand + " — " : "")
                        + (articleNum != null ? "Art. " + articleNum : "Scan IA");
                // AssetTag = numero article si dispo
                String assetTag = (articleNum != null && !articleNum.isEmpty())
                        ? articleNum : "ASSET-" + System.currentTimeMillis();

                saveAssetFull(finalName, "Informatique", finalDesc, assetTag, qty, reason,
                        poNumber, poDesc, supplier,
                        articleNum, brand, poOnLabel);
            } else {
                // Étiquette skippée → save avec les infos facture seulement
                saveAsset(name, reason, poNumber, poDesc, supplier);
            }
        }
    }

    private void askScanLabel(final String poNumber, final String poDescription, final String supplier) {
        pendingPoNumber = poNumber;
        pendingPoDescription = poDescription;
        pendingSupplier = supplier;

        final String name = pendingName, reason = pendingReason;

        String msg = "Équipement : " + name + "\n";
        if (poNumber != null) msg += "PO facture : " + poNumber + "\n";
        msg += "\nPrends en photo l'étiquette du carton pour obtenir le nom exact du produit, "
             + "la quantité, la référence article et la marque.";

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🏷️ Scanner l'étiquette du carton ?")
                .setMessage(msg)
                .setPositiveButton("📷 Scanner l'étiquette", (d, w) -> launchLabelScan(poNumber))
                .setNegativeButton("Enregistrer sans étiquette", (d, w) -> {
                    pendingName = null; pendingReason = null;
                    pendingPoNumber = null; pendingPoDescription = null; pendingSupplier = null;
                    saveAsset(name, reason, poNumber, poDescription, supplier);
                })
                .setCancelable(false)
                .show();
    }

    private void launchLabelScan(String expectedPo) {
        Intent i = new Intent(this, PackageLabelActivity.class);
        if (expectedPo != null) i.putExtra(PackageLabelActivity.EXTRA_EXPECTED_PO, expectedPo);
        startActivityForResult(i, REQ_LABEL_SCAN);
    }

    /** Insert final en base avec (potentiellement) les champs PO. */
    private void saveAsset(String name, String reason,
                           String poNumber, String poDescription, String receivedFrom) {
        saveAssetFull(name, "Informatique", null,
                "ASSET-" + System.currentTimeMillis(), 1, reason,
                poNumber, poDescription, receivedFrom,
                null, null, null);
    }

    /** Insert final enrichi avec toutes les infos disponibles (facture + étiquette). */
    private void saveAssetFull(String name, String category, String description,
                               String assetTag, int quantity, String reason,
                               String poNumber, String poDescription, String receivedFrom,
                               String articleNumber, String brand, String packagePoNumber) {
        controller.addProduct(name, category, null, description, assetTag, quantity, 0.0, "", "", reason,
                poNumber, poDescription, receivedFrom,
                articleNumber, brand, packagePoNumber,
                () -> {
                    String suffix = poNumber != null ? " (lié à " + poNumber + ")" : "";
                    Toast.makeText(this, name + " x" + quantity + " ajouté au stock" + suffix, Toast.LENGTH_LONG).show();
                    NotificationHelper.showNotification(this,
                            "StockIT — Réception",
                            name + " x" + quantity + " enregistré" + suffix,
                            (int) System.currentTimeMillis());

                    if (poNumber != null) {
                        SlackNotifier.send(":white_check_mark: [StockIT] " + name
                                + " x" + quantity + " ajouté au stock et rattaché à " + poNumber
                                + (receivedFrom != null ? " (" + receivedFrom + ")" : ""));
                    }

                    // Email de synthèse d'ajout au stock (match IA -> asset enregistré).
                    com.example.stockit.util.StockItReporter.sendEvent(this,
                            "Nouvel équipement enregistré : " + name + " x" + quantity,
                            "✅ Un nouvel équipement vient d'être ajouté au stock via scan IA.\n\n"
                                    + "• Nom         : " + name + "\n"
                                    + "• Quantité    : " + quantity + "\n"
                                    + "• PO lié      : " + (poNumber != null ? poNumber : "aucun") + "\n"
                                    + "• Fournisseur : " + (receivedFrom != null ? receivedFrom : "non renseigné"),
                            "wissem.soussia@vista.com");

                    StringBuilder body = new StringBuilder();
                    body.append("• Nom : ").append(name).append("\n");
                    body.append("• Quantité : ").append(quantity).append("\n");
                    if (brand != null)          body.append("• Marque : ").append(brand).append("\n");
                    if (articleNumber != null)  body.append("• Art.-No. : ").append(articleNumber).append("\n");
                    body.append("• Motif : ").append(reason).append("\n");
                    if (poNumber != null)       body.append("• PO facture : ").append(poNumber).append("\n");
                    if (poDescription != null)  body.append("• Description PO : ").append(poDescription).append("\n");
                    if (packagePoNumber != null)body.append("• PO étiquette : ").append(packagePoNumber).append("\n");
                    if (receivedFrom != null)   body.append("• Fournisseur : ").append(receivedFrom).append("\n");

                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("🟢 Équipement enregistré")
                            .setMessage(body.toString())
                            .setPositiveButton("OK", (d, w) -> finish())
                            .show();
                });
    }

    private void setBusy(boolean busy, String label) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnCapture.setEnabled(!busy);
        if (label != null) txtResult.setText(label);
    }

    private static byte[] toJpegBytes(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    // ================================================================
    //  Vista scan overlay — faisceau bleu, ciblage bleu marine, badge Match
    // ================================================================

    /** Faisceau bleu Vista qui balaie l'écran verticalement pendant l'analyse. */
    private void startScanBeam() {
        if (scanBeam == null) return;
        if (scanGrid != null) scanGrid.setVisibility(View.VISIBLE);
        scanBeam.setVisibility(View.VISIBLE);
        // On lance l'animation dès que la vue a une hauteur connue.
        scanBeam.post(() -> {
            View parent = (View) scanBeam.getParent();
            if (parent == null) return;
            float from = 0f;
            float to   = parent.getHeight() - scanBeam.getHeight() - 40f;
            if (to <= from) to = from + 400f;
            beamAnimator = android.animation.ObjectAnimator.ofFloat(
                    scanBeam, "translationY", from, to);
            beamAnimator.setDuration(1400);
            beamAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            beamAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            beamAnimator.setInterpolator(new LinearInterpolator());
            beamAnimator.start();
        });
    }

    private void stopScanBeam() {
        if (beamAnimator != null) {
            beamAnimator.cancel();
            beamAnimator = null;
        }
        if (scanBeam != null) scanBeam.setVisibility(View.GONE);
        if (scanGrid != null) scanGrid.setVisibility(View.GONE);
    }

    /** Cadre de ciblage bleu marine autour de l'objet détecté. */
    private void showTargetFrame() {
        if (scanTargetFrame == null) return;
        scanTargetFrame.setVisibility(View.VISIBLE);
        scanTargetFrame.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.target_frame_lock));
    }

    private void hideTargetFrame() {
        if (scanTargetFrame == null) return;
        scanTargetFrame.clearAnimation();
        scanTargetFrame.setVisibility(View.INVISIBLE);
    }

    /** Badge "Match IA" vert menthe / turquoise avec animation d'entrée fluide. */
    private void showMatchSuccess(String label) {
        if (scanMatchBadge == null) return;
        if (scanMatchText != null && label != null) scanMatchText.setText(label);
        scanMatchBadge.setVisibility(View.VISIBLE);
        scanMatchBadge.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.match_success_pop));
        scanMatchBadge.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        vibrateSuccess();
        // Ondes concentriques turquoise (effet radar).
        fireWaveRing(scanWaveRing1, 0);
        fireWaveRing(scanWaveRing2, 350);
    }

    /** Pattern de vibration double "tap-tap" pour signaler un Match IA franc.
     *  Protégé par try/catch : une SecurityException (perm révoquée par l'utilisateur
     *  ou politique OEM) ne doit jamais crasher le flow de scan. */
    private void vibrateSuccess() {
        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            long[] pattern = {0, 40, 60, 40};
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vib.vibrate(pattern, -1);
            }
        } catch (SecurityException | IllegalStateException ignored) {
            // Vibration best-effort : silencieux si non autorisé.
        }
    }

    private void hideMatchBadge() {
        if (scanMatchBadge == null) return;
        scanMatchBadge.clearAnimation();
        scanMatchBadge.setVisibility(View.GONE);
        if (scanWaveRing1 != null) { scanWaveRing1.clearAnimation(); scanWaveRing1.setVisibility(View.GONE); }
        if (scanWaveRing2 != null) { scanWaveRing2.clearAnimation(); scanWaveRing2.setVisibility(View.GONE); }
    }

    /** Une onde concentrique qui part du centre — scale 0.6→2.4 + fade. */
    private void fireWaveRing(final View ring, long delayMs) {
        if (ring == null) return;
        ring.postDelayed(() -> {
            ring.setVisibility(View.VISIBLE);
            ring.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_ripple));
        }, delayMs);
    }

    /** Bascule le flash caméra (torche). Icône colorée en ambre quand actif. */
    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, "Flash indisponible", Toast.LENGTH_SHORT).show();
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        if (scanTorch != null) {
            scanTorch.setSelected(torchOn);
            scanTorch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /**
     * Pinch-to-zoom : ScaleGestureDetector qui pilote CameraX ZoomState.
     * Un petit chip Vista en haut à gauche affiche le facteur courant.
     */
    private void setupPinchZoom() {
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (camera == null) return true;
                        androidx.camera.core.ZoomState state =
                                camera.getCameraInfo().getZoomState().getValue();
                        if (state == null) return true;
                        float current = state.getZoomRatio();
                        float target  = Math.max(state.getMinZoomRatio(),
                                Math.min(state.getMaxZoomRatio(),
                                        current * detector.getScaleFactor()));
                        camera.getCameraControl().setZoomRatio(target);
                        showZoomChip(target);
                        return true;
                    }
                });

        if (previewView != null) {
            previewView.setOnTouchListener((v, event) -> {
                scaleDetector.onTouchEvent(event);
                // Consomme le geste multi-touch, laisse passer les single taps.
                return event.getPointerCount() > 1;
            });
        }
    }

    /** Affiche le chip de zoom (fade out automatique après 1.2s d'inactivité). */
    private void showZoomChip(float ratio) {
        if (scanZoomChip == null) return;
        scanZoomChip.setText(String.format(java.util.Locale.US, "%.1f×", ratio));
        scanZoomChip.setVisibility(View.VISIBLE);
        scanZoomChip.animate().alpha(1f).setDuration(120).start();
        scanZoomChip.removeCallbacks(hideZoomChip);
        scanZoomChip.postDelayed(hideZoomChip, 1200);
    }

    private final Runnable hideZoomChip = () -> {
        if (scanZoomChip == null) return;
        scanZoomChip.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> scanZoomChip.setVisibility(View.GONE)).start();
    };

    @Override
    protected void onDestroy() {
        stopScanBeam();
        super.onDestroy();
    }
}
