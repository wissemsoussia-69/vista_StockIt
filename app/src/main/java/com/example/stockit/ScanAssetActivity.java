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
import com.example.stockit.util.JiraUrlHelper;
import com.example.stockit.util.KitAntiOubliDialog;
import com.example.stockit.util.SlackNotifier;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

public class ScanAssetActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 4242;

    private PreviewView previewView;
    private TextView txtResult;
    private ProgressBar progress;
    private Button btnCapture;
    private ImageCapture imageCapture;
    private MainController controller;

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

        previewView  = findViewById(R.id.scanPreview);
        txtResult   = findViewById(R.id.scanResult);
        progress    = findViewById(R.id.scanProgress);
        btnCapture  = findViewById(R.id.scanCapture);
        controller  = MainController.getInstance(this);

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
            Toast.makeText(this, R.string.toast_camera_permission_denied, Toast.LENGTH_SHORT).show();
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
                if (scanTorch != null && camera != null
                        && !camera.getCameraInfo().hasFlashUnit()) {
                    scanTorch.setVisibility(View.GONE);
                }
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, getString(R.string.toast_camera_error_ex, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capture() {
        if (imageCapture == null) return;
        setBusy(true, "Capture...");
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
                        Toast.makeText(ScanAssetActivity.this, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show();
                        SlackNotifier.send(":warning: [StockIT] Camera capture failed: " + e.getMessage());
                        com.example.stockit.util.StockItReporter.sendEvent(ScanAssetActivity.this,
                                "Camera incident (equipment scan)",
                                "Camera capture failed during an equipment scan.\n\n"
                                    + "- Technical details: " + e.getMessage() + "\n"
                                    + "- Screen          : ScanAssetActivity\n\n"
                                    + "Check camera permissions and device status.",
                                "wissem.soussia@vista.com");
                    }
                });
    }

    private void analyze(byte[] jpeg) {
        setBusy(true, "AI analysis (Cimpress)...");
        GeminiGatewayClient.identify(this, jpeg, (name, error) ->
                runOnUiThread(() -> {
                    setBusy(false, null);
                    stopScanBeam();
                    if (error != null || name == null || name.isEmpty()) {
                        String err = error != null ? error : "empty response";
                        txtResult.setText("Analysis failed (" + err + ")");
                        SlackNotifier.send(":x: [StockIT] AI scan anomaly: " + err);
                        com.example.stockit.util.StockItReporter.sendEvent(ScanAssetActivity.this,
                            "Claude AI detection anomaly",
                                "AI could not identify the scannedd object.\n\n"
                                    + "- Returned error: " + err + "\n\n"
                                    + "Possible causes: blurry image, incorrect framing, model limitation.\n"
                                    + "Keep this case for prompt tuning.",
                                "wissem.soussia@vista.com");
                        return;
                    }
                    txtResult.setText("Detected object: " + name);
                    showTargetFrame();
                    showMatchSuccess("Match : " + name);
                    handleDetected(name);
                }));
    }

    private void handleDetected(final String detectedName) {
        if (KitAntiOubliDialog.requiresKit(detectedName)) {
            KitAntiOubliDialog.show(this, detectedName, (power, hdmi) -> {
                if (power && hdmi) {
                    askScanFacture(detectedName, "Complete kit (Power + HDMI)");
                } else {
                    txtResult.setText("Incompletee kit - validation blocked.");
                    SlackNotifier.send(":rotating_light: [StockIT] Incompletee anti-forget kit for a screen (Power=" + power + ", HDMI=" + hdmi + ")");
                    com.example.stockit.util.StockItReporter.sendEvent(this,
                                "Incompletee anti-forget kit (screen)",
                                "A screen was scannedd without all mandatory accessories.\n\n"
                                    + "- Power adapter present: " + power + "\n"
                                    + "- HDMI cable present   : " + hdmi + "\n\n"
                                    + "A Jira ticket was automatically created to trace this incident.",
                            "wissem.soussia@vista.com");
                    JiraClient.createTask(
                            BuildConfig.JIRA_PROJECT_KEY,
                            "[StockIT] Missing accessories kit for a screen",
                            "A technician scannedd a screen without confirming all required accessories (Power=" + power + ", HDMI=" + hdmi + ").",
                            (ok, res) -> runOnUiThread(() -> {
                                if (ok) {
                                    String key = res;
                                        txtResult.setText("Incompletee kit - Jira ticket created: " + key);
                                    new androidx.appcompat.app.AlertDialog.Builder(this)
                                            .setTitle("Jira ticket created")
                                            .setMessage("Key: " + key + "\n\nProject: " + BuildConfig.JIRA_PROJECT_KEY
                                                + "\nURL : " + JiraUrlHelper.browseUrl(key))
                                            .setPositiveButton("OK", null)
                                            .show();
                                } else {
                                    txtResult.setText("Jira failure - " + res);
                                    SlackNotifier.send(":warning: [StockIT] Jira creation failed: " + res);
                                    new androidx.appcompat.app.AlertDialog.Builder(this)
                                            .setTitle("Jira failure - project " + BuildConfig.JIRA_PROJECT_KEY)
                                            .setMessage("Raw details returned by Jira:\n\n" + res)
                                            .setPositiveButton("OK", null)
                                            .show();
                                }
                            }));
                }
            });
        } else {
            askScanFacture(detectedName, "StockIT AI scan");
        }
    }


    private static final int REQ_FACTURE_SCAN = 7070;

    private String pendingName;
    private String pendingReason;
    private String pendingIaHint;

    private void askScanFacture(final String name, final String reason) {
        pendingName = name;
        pendingReason = reason;
        pendingIaHint = name;
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Scan linked invoice?")
            .setMessage("Equipment: " + name + "\n\n"
                + "Take a photo of the invoice to automatically link the PO. "
                + "Otherwise the equipment will be added without PO.")
            .setPositiveButton("Scan invoice", (d, w) -> launchFactureScan(name))
            .setNegativeButton("Save without PO", (d, w) -> saveAsset(name, reason, null, null, null))
                .setCancelable(false)
                .show();
    }

    private void launchFactureScan(String name) {
        Intent i = new Intent(this, ReceivePackageActivity.class);
        i.putExtra(ReceivePackageActivity.EXTRA_EQUIPMENT_NAME, name);
        startActivityForResult(i, REQ_FACTURE_SCAN);
    }


    private static final int REQ_LABEL_SCAN = 7071;

    private String pendingPoNumber;
    private String pendingPoDescription;
    private String pendingSupplier;
    private String pendingBrand;
    private String pendingModel;
    private String pendingInvoiceNumber;
    private String pendingInvoiceDate;
    private java.util.ArrayList<String> pendingSerials;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FACTURE_SCAN) {
            if (pendingName == null) return;
            String poNumber = null, poDesc = null, supplier = null;
            String brand = null, model = null, invoiceNumber = null, invoiceDate = null;
            java.util.ArrayList<String> serials = null;
            if (resultCode == RESULT_OK && data != null) {
                poNumber      = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_NUMBER);
                poDesc        = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_DESCRIPTION);
                supplier      = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_SUPPLIER);
                brand         = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_BRAND);
                model         = data.getStringExtra(POSelectionActivity.EXTRA_SELECTED_MODEL);
                invoiceNumber = data.getStringExtra(POSelectionActivity.EXTRA_INVOICE_NUMBER);
                invoiceDate   = data.getStringExtra(POSelectionActivity.EXTRA_INVOICE_DATE);
                serials       = data.getStringArrayListExtra(POSelectionActivity.EXTRA_SELECTED_SERIALS);
            }
            pendingBrand         = brand;
            pendingModel         = model;
            pendingInvoiceNumber = invoiceNumber;
            pendingInvoiceDate   = invoiceDate;
            pendingSerials       = serials;
            askScanLabel(poNumber, poDesc, supplier);
            return;
        }

        if (requestCode == REQ_LABEL_SCAN) {
            if (pendingName == null) return;
            String name = pendingName, reason = pendingReason;
            String poNumber = pendingPoNumber, poDesc = pendingPoDescription, supplier = pendingSupplier;
            String brand = pendingBrand, model = pendingModel;
            String invoiceNumber = pendingInvoiceNumber, invoiceDate = pendingInvoiceDate;
            java.util.ArrayList<String> serials = pendingSerials;
            String iaHint = pendingIaHint;
            pendingName = null; pendingReason = null;
            pendingPoNumber = null; pendingPoDescription = null; pendingSupplier = null;
            pendingBrand = null; pendingModel = null;
            pendingInvoiceNumber = null; pendingInvoiceDate = null; pendingSerials = null;
            pendingIaHint = null;

            if (resultCode == RESULT_OK && data != null) {
                String prodName    = data.getStringExtra(PackageLabelActivity.EXTRA_PRODUCT_NAME);
                int    qty         = data.getIntExtra(PackageLabelActivity.EXTRA_QUANTITY, 1);
                String articleNum  = data.getStringExtra(PackageLabelActivity.EXTRA_ARTICLE_NUMBER);
                String brandLabel  = data.getStringExtra(PackageLabelActivity.EXTRA_BRAND);
                String poOnLabel   = data.getStringExtra(PackageLabelActivity.EXTRA_PO_ON_LABEL);
                String upc         = data.getStringExtra(PackageLabelActivity.EXTRA_UPC);

                String finalName = (prodName != null && !prodName.isEmpty()) ? prodName : name;
                String finalBrand = (brandLabel != null && !brandLabel.isEmpty()) ? brandLabel : brand;
                String finalDesc = (finalBrand != null ? finalBrand + " - " : "")
                    + (articleNum != null ? "Art. " + articleNum : "AI scan");
                String assetTag = (articleNum != null && !articleNum.isEmpty())
                        ? articleNum : "ASSET-" + System.currentTimeMillis();

                saveAssetFull(finalName, "IT", finalDesc, assetTag, qty, reason,
                        poNumber, poDesc, supplier,
                        articleNum, brandLabel, poOnLabel,
                        finalBrand, model, invoiceNumber, invoiceDate, upc, serials, iaHint);
            } else {
                saveAssetFull(name, "IT", null,
                        "ASSET-" + System.currentTimeMillis(),
                        serials != null && !serials.isEmpty() ? serials.size() : 1, reason,
                        poNumber, poDesc, supplier,
                        null, null, null,
                        brand, model, invoiceNumber, invoiceDate, null, serials, iaHint);
            }
        }
    }

    private void askScanLabel(final String poNumber, final String poDescription, final String supplier) {
        pendingPoNumber = poNumber;
        pendingPoDescription = poDescription;
        pendingSupplier = supplier;

        final String name = pendingName, reason = pendingReason;

        String msg = "Equipment: " + name + "\n";
        if (poNumber != null) msg += "Invoice PO: " + poNumber + "\n";
        msg += "\nTake a photo of the package label to get the exact product name, "
               + "quantity, item reference, and brand.";

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Scan package label?")
                .setMessage(msg)
            .setPositiveButton("Scan label", (d, w) -> launchLabelScan(poNumber))
            .setNegativeButton("Save without label", (d, w) -> {
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

    private void saveAsset(String name, String reason,
                           String poNumber, String poDescription, String receivedFrom) {
        saveAssetFull(name, "IT", null,
                "ASSET-" + System.currentTimeMillis(), 1, reason,
                poNumber, poDescription, receivedFrom,
                null, null, null,
                null, null, null, null, null, null, name);
    }

    private void saveAssetFull(String name, String category, String description,
                               String assetTag, int quantity, String reason,
                               String poNumber, String poDescription, String receivedFrom,
                               String articleNumber, String brand, String packagePoNumber,
                               String jiraBrand, String jiraModel,
                               String invoiceNumber, String invoiceDate,
                               String upc,
                               java.util.ArrayList<String> serials,
                               String iaHint) {
        final android.content.Context appCtx = getApplicationContext();
        final long entryStartMs = System.currentTimeMillis();
        final boolean withPo = poNumber != null && !poNumber.isEmpty();
                    final String normalizedName = com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(name);
                    controller.addProduct(normalizedName, category, null, description, assetTag, quantity, 0.0, "", "", reason,
                poNumber, poDescription, receivedFrom,
                articleNumber, brand, packagePoNumber,
                () -> {
                    com.example.stockit.util.AnalyticsHelper.logEntryValidated(
                                appCtx, normalizedName, quantity, withPo,
                            System.currentTimeMillis() - entryStartMs);

                    String eventBarcode = (articleNumber != null && !articleNumber.isEmpty())
                            ? articleNumber : assetTag;
                    com.example.stockit.util.StockItReporter.sendStockEvent(
                            appCtx, eventBarcode, normalizedName, quantity);

                        String suffix = poNumber != null ? " (linked to " + poNumber + ")" : "";
                        Toast.makeText(appCtx, normalizedName + " x" + quantity + " added to stock" + suffix, Toast.LENGTH_LONG).show();
                    NotificationHelper.showNotification(appCtx,
                            "StockIT - Receiving",
                            normalizedName + " x" + quantity + " recorded" + suffix,
                            (int) System.currentTimeMillis());

                    if (poNumber != null) {
                        SlackNotifier.send(":white_check_mark: [StockIT] " + normalizedName
                            + " x" + quantity + " added to stock and linked to " + poNumber
                                + (receivedFrom != null ? " (" + receivedFrom + ")" : ""));
                    }

                    com.example.stockit.util.StockItReporter.sendEvent(appCtx,
                                "New equipment recorded: " + normalizedName + " x" + quantity,
                                "New equipment has been added to stock via AI scan.\n\n"
                                    + "- Name        : " + normalizedName + "\n"
                                    + "- Quantity    : " + quantity + "\n"
                                    + "- Linked PO   : " + (poNumber != null ? poNumber : "none") + "\n"
                                    + "- Supplier    : " + (receivedFrom != null ? receivedFrom : "not provided"),
                            "wissem.soussia@vista.com");

                    StringBuilder body = new StringBuilder();
                    body.append("- Name: ").append(normalizedName).append("\n");
                    body.append("- Quantity: ").append(quantity).append("\n");
                    if (brand != null)          body.append("- Brand: ").append(brand).append("\n");
                    if (articleNumber != null)  body.append("- Art.-No. : ").append(articleNumber).append("\n");
                    body.append("- Reason: ").append(reason).append("\n");
                    if (poNumber != null)       body.append("- Invoice PO: ").append(poNumber).append("\n");
                    if (poDescription != null)  body.append("- PO Description: ").append(poDescription).append("\n");
                    if (packagePoNumber != null)body.append("- Label PO: ").append(packagePoNumber).append("\n");
                    if (receivedFrom != null)   body.append("- Supplier: ").append(receivedFrom).append("\n");
                    android.util.Log.i("ScanAsset", "Asset saved locally:\n" + body);

                        createJiraAssetsInBackground(normalizedName, jiraBrand, jiraModel,
                            poNumber, invoiceNumber, invoiceDate, receivedFrom, upc,
                            serials, quantity, iaHint);
                });

        finish();
    }


    private int detectJiraTypeId(String detectedName) {
        if (detectedName == null) return -1;
        String n = detectedName.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("ordinateur") || n.contains("desktop") || n.contains("laptop")
                || n.contains("pc ") || n.equals("pc") || n.contains("tour")
                || n.contains("mini-pc") || n.contains("micro-pc") || n.contains("notebook")) {
            return 939;
        }
        if (n.contains("screen") || n.contains("monitor")
            || n.contains("display")) {
            return 938;
        }
        if (n.contains("mouse") || n.contains("keyboard") || n.contains("cable")
            || n.contains("webcam") || n.contains("headset")
                || n.contains("headset") || n.contains("headphone") || n.contains("earphone")
                || n.contains("earbud") || n.contains("earphone")
            || n.contains("mouse") || n.contains("keyboard")
                || n.contains("hub") || n.contains("dock") || n.contains("docking")
            || n.contains("adapter")
            || n.contains("charger") || n.contains("power")
                || n.contains("hdmi") || n.contains("displayport") || n.contains("thunderbolt")
            || n.contains("stereo") || n.contains("audio") || n.contains("mic ")
            || n.contains("microphone") || n.contains("speaker")
                || n.contains("impact") || n.contains("sennheiser") || n.contains("epos")
                || n.contains("logitech") || n.contains("jabra") || n.contains("plantronics")) {
            return 940;
        }
        return -1;
    }

    private void createJiraAssetsInBackground(final String detectedName,
                                              final String brand,
                                              final String model,
                                              final String poNumber,
                                              final String invoiceNumber,
                                              final String invoiceDate,
                                              final String vendorName,
                                              final String upc,
                                              final java.util.ArrayList<String> serials,
                                              final int fallbackQuantity,
                                              final String iaHint) {
        int resolvedTypeId = detectJiraTypeId(detectedName);
        if (resolvedTypeId < 0 && iaHint != null) {
            resolvedTypeId = detectJiraTypeId(iaHint);
            if (resolvedTypeId >= 0) {
                android.util.Log.i("ScanAsset", "detectJiraTypeId : match via iaHint '"
                        + iaHint + "' \u2192 typeId=" + resolvedTypeId
                        + " (le nom pr\u00e9cis '" + detectedName + "' n'\u00e9tait pas class\u00e9)");
            }
        }
        final int typeId = resolvedTypeId;
        if (typeId < 0) {
            android.util.Log.i("ScanAsset", "detectJiraTypeId : type non g\u00e9r\u00e9 par Jira Assets, skip \u2014 "
                    + detectedName + " (hint=" + iaHint + ")");
            return;
        }

        java.util.List<String> realSerials = new java.util.ArrayList<>();
        if (serials != null) {
            for (String s : serials) {
                String clean = cleanToken(s);
                if (clean != null && !realSerials.contains(clean)) realSerials.add(clean);
            }
        }

        String cleanUpc = cleanToken(upc);
        int objectCount = realSerials.isEmpty() ? Math.max(1, fallbackQuantity) : realSerials.size();

        String assetTypeLabel = (typeId == 939 ? "Desktop" : (typeId == 938 ? "Monitor" : "Peripheral"));
        java.util.List<java.util.Map<String, String>> batch = new java.util.ArrayList<>();
        for (int i = 0; i < objectCount; i++) {
            String sn = i < realSerials.size() ? realSerials.get(i) : null;
            java.util.Map<String, String> a = new java.util.LinkedHashMap<>();
            a.put("Name", buildAssetName(detectedName, brand, model, sn, i + 1, objectCount));
            if (sn != null && !sn.isEmpty()) a.put("Serial Number", sn);
            if (brand != null && !brand.isEmpty()) {
                a.put("Device Name",  brand);
                a.put("Brand",        brand);
            }
            if (model != null && !model.isEmpty())         a.put("Asset Model",     model);
            a.put("Asset Status",         "In stock");
            a.put("Asset Sub-Status",     "In stock - Brand New");
            a.put("Asset Type",           assetTypeLabel);
            a.put("Geo Location",         "Tunis");
            if (poNumber != null && !poNumber.isEmpty())         a.put("PO Number",       poNumber);
            if (invoiceDate != null && !invoiceDate.isEmpty())   a.put("Invoice Date",    invoiceDate);
            if (invoiceNumber != null && !invoiceNumber.isEmpty()) a.put("Invoice Number", invoiceNumber);
            if (vendorName != null && !vendorName.isEmpty())     a.put("Vendor Name",     vendorName);
            if (cleanUpc != null && !cleanUpc.isEmpty())         a.put("UPC",             cleanUpc);
            batch.add(a);
        }

        final int total = batch.size();
        final android.content.Context appCtx = getApplicationContext();
        final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final int notifId = (int) (System.currentTimeMillis() & 0x7fffffff);

        com.example.stockit.util.JiraNotifier.showProgress(appCtx, notifId, 0, total);
        Toast.makeText(appCtx, getString(R.string.toast_jira_creating, total), Toast.LENGTH_SHORT).show();
        android.util.Log.i("ScanAsset", "Jira Assets batch : typeId=" + typeId + " count=" + total);

        com.example.stockit.util.JiraAssetsClient.createAssetsBatch(
                typeId,
                batch,
                (done, tot) -> mainHandler.post(() ->
                        com.example.stockit.util.JiraNotifier.showProgress(appCtx, notifId, done, tot)),
                (successCount, failureCount, createdKeys, errorSample) -> mainHandler.post(() -> {
                    String firstKey = createdKeys.isEmpty() ? null : createdKeys.get(0);
                    if (failureCount == 0 && successCount > 0) {
                        com.example.stockit.util.JiraNotifier.showSuccess(appCtx, notifId,
                                successCount, total, firstKey);
                        if (firstKey != null) {
                            com.example.stockit.util.RecentAssetsStore.add(appCtx,
                                    detectedName, firstKey, total);
                        }
                        com.example.stockit.util.JiraTicketMatcher.findAndNotify(appCtx, detectedName);
                        Toast.makeText(appCtx,
                                "Jira Assets: " + successCount + "/" + total + " created"
                                        + (firstKey != null ? " (" + firstKey + ")" : ""),
                                Toast.LENGTH_LONG).show();
                    } else if (successCount > 0) {
                        com.example.stockit.util.JiraNotifier.showPartial(appCtx, notifId,
                                successCount, failureCount, total, firstKey, errorSample);
                        if (firstKey != null) {
                            com.example.stockit.util.RecentAssetsStore.add(appCtx,
                                    detectedName, firstKey, successCount);
                        }
                        com.example.stockit.util.JiraTicketMatcher.findAndNotify(appCtx, detectedName);
                        Toast.makeText(appCtx,
                                "Warning: Jira Assets partial: " + successCount + " OK, "
                                    + failureCount + " failed",
                                Toast.LENGTH_LONG).show();
                    } else {
                        com.example.stockit.util.JiraNotifier.showError(appCtx, notifId, errorSample);
                        Toast.makeText(appCtx,
                                "Jira Assets failed: " + errorSample,
                                Toast.LENGTH_LONG).show();
                    }
                }));
    }

    private static String cleanToken(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        if ("null".equalsIgnoreCase(v)) return null;
        if ("none".equalsIgnoreCase(v)) return null;
        if ("none entered".equalsIgnoreCase(v)) return null;
        if ("n/a".equalsIgnoreCase(v)) return null;
        return v;
    }

    private static String buildAssetName(String detectedName,
                                         String brand,
                                         String model,
                                         String serial,
                                         int index,
                                         int total) {
        String base = cleanToken(model);
        if (base == null) base = cleanToken(detectedName);
        if (base == null) base = cleanToken(brand);
        if (base == null) base = "Peripheral";

        String b = cleanToken(brand);
        if (b != null && !base.toLowerCase(java.util.Locale.ROOT).contains(b.toLowerCase(java.util.Locale.ROOT))) {
            base = b + " " + base;
        }

        String sn = cleanToken(serial);
        if (sn != null) return base + " - SN " + sn;
        if (total <= 1) return base;
        return base + " #" + String.format(java.util.Locale.ROOT, "%03d", index);
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


    private void startScanBeam() {
        if (scanBeam == null) return;
        if (scanGrid != null) scanGrid.setVisibility(View.VISIBLE);
        scanBeam.setVisibility(View.VISIBLE);
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

    private void showMatchSuccess(String label) {
        if (scanMatchBadge == null) return;
        if (scanMatchText != null && label != null) scanMatchText.setText(label);
        scanMatchBadge.setVisibility(View.VISIBLE);
        scanMatchBadge.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.match_success_pop));
        scanMatchBadge.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        vibrateSuccess();
        fireWaveRing(scanWaveRing1, 0);
        fireWaveRing(scanWaveRing2, 350);
    }

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
        }
    }

    private void hideMatchBadge() {
        if (scanMatchBadge == null) return;
        scanMatchBadge.clearAnimation();
        scanMatchBadge.setVisibility(View.GONE);
        if (scanWaveRing1 != null) { scanWaveRing1.clearAnimation(); scanWaveRing1.setVisibility(View.GONE); }
        if (scanWaveRing2 != null) { scanWaveRing2.clearAnimation(); scanWaveRing2.setVisibility(View.GONE); }
    }

    private void fireWaveRing(final View ring, long delayMs) {
        if (ring == null) return;
        ring.postDelayed(() -> {
            ring.setVisibility(View.VISIBLE);
            ring.startAnimation(AnimationUtils.loadAnimation(this, R.anim.wave_ripple));
        }, delayMs);
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, R.string.toast_flash_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        torchOn = !torchOn;
        camera.getCameraControl().enableTorch(torchOn);
        if (scanTorch != null) {
            scanTorch.setSelected(torchOn);
            scanTorch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

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
                return event.getPointerCount() > 1;
            });
        }
    }

    private void showZoomChip(float ratio) {
        if (scanZoomChip == null) return;
        scanZoomChip.setText(String.format(java.util.Locale.US, "%.1fx", ratio));
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
