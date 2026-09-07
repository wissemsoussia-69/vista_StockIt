package com.example.stockit;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
import com.example.stockit.model.JiraTicket;
import com.example.stockit.model.Product;
import com.example.stockit.util.GeminiGatewayClient;
import com.example.stockit.util.JiraAssetsClient;
import com.example.stockit.util.JiraClient;
import com.example.stockit.util.JiraReader;
import com.example.stockit.util.SlackNotifier;
import com.example.stockit.util.TicketMatcher;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.text.Normalizer;

public class ScanOutActivity extends AppCompatActivity {

    private static final String TAG = "ScanOut";
    private static final int REQ_CAMERA = 6262;
    private static final int REQ_TICKET_PICK = 6263;

    private PreviewView preview;
    private TextView    status;
    private ProgressBar progress;
    private Button      btnCapture;

    private ImageCapture imageCapture;
    private MainController controller;

    private Product pendingProduct;      // Matched product from local DB
    private int     remainingQty;        // Remaining quantity to assign
    private String  outboundRecipientName;
    private String  pendingTracePo;
    private String  pendingTraceSerial;
    private final List<Assignment> journal = new ArrayList<>();
    private final Set<String> transitionedTickets = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_out);

        preview   = findViewById(R.id.soPreview);
        status    = findViewById(R.id.soStatus);
        progress  = findViewById(R.id.soProgress);
        btnCapture= findViewById(R.id.soBtnCapture);
        Button btnCancel = findViewById(R.id.soBtnCancel);

        controller = MainController.getInstance(this);

        btnCapture.setOnClickListener(v -> capture());
        btnCancel.setOnClickListener(v -> finish());

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
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                CameraSelector sel = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                provider.unbindAll();
                provider.bindToLifecycle(this, sel, p, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, getString(R.string.toast_camera_error_ex, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void capture() {
        if (imageCapture == null) return;
        setBusy(true, "Claude Opus 4 analysis...");
        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                        byte[] jpeg = toJpegBytes(image);
                        image.close();
                        analyze(jpeg);
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        setBusy(false, "Capture: " + e.getMessage());
                    }
                });
    }

    private void analyze(byte[] jpeg) {
        GeminiGatewayClient.identify(this, jpeg, (name, error) -> runOnUiThread(() -> {
            setBusy(false, null);
            if (error != null || name == null || name.isEmpty()) {
                showError("Analysis failed: " + (error != null ? error : "empty response"));
                return;
            }
            status.setText("Detected: " + com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(name));
            findInStock(name);
        }));
    }

    private void findInStock(final String detectedName) {
        controller.getStock(products -> runOnUiThread(() -> {
            Product match = null;
            String needle = normalizeText(detectedName);
            int bestScore = -1;

            for (Product p : products) {
                String productName = p.getName();
                if (productName == null || productName.trim().isEmpty()) continue;
                int score = matchScore(needle, normalizeText(productName));
                if (score > bestScore) {
                    bestScore = score;
                    match = p;
                }
            }

            if (bestScore < 1) match = null;

            if (match == null || match.getQuantity() <= 0) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dlg_title_stock_empty)
                        .setMessage(getString(R.string.dlg_msg_stock_empty, detectedName))
                        .setPositiveButton(R.string.action_ok, (d, w) -> finish())
                        .show();
                return;
            }
            pendingProduct = match;
            askQuantity();
        }));
    }

    private static int matchScore(String detected, String stockName) {
        if (detected == null || detected.isEmpty() || stockName == null || stockName.isEmpty()) return 0;
        if (stockName.contains(detected) || detected.contains(stockName)) return 100;

        String[] tokens = detected.split("\\s+");
        int common = 0;
        for (String t : tokens) {
            if (t.length() < 3) continue;
            if (stockName.contains(t)) common++;
        }
        return common;
    }

    private static String normalizeText(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private void askQuantity() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.hint_ex_number);

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_title_quantity_out,
                com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(pendingProduct.getName())))
                .setMessage(getString(R.string.dlg_msg_stock_available, pendingProduct.getQuantity()))
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(R.string.action_continue, (d, w) -> {
                    int q;
                    try { q = Integer.parseInt(input.getText().toString().trim()); }
                    catch (NumberFormatException e) { q = 0; }
                    if (q <= 0) {
                        Toast.makeText(this, R.string.toast_invalid_qty, Toast.LENGTH_SHORT).show();
                        askQuantity();
                        return;
                    }
                    if (q > pendingProduct.getQuantity()) {
                        Toast.makeText(this,
                            "Insufficient stock (available: " + pendingProduct.getQuantity() + ")",
                                Toast.LENGTH_LONG).show();
                        askQuantity();
                        return;
                    }
                    final int qFinal = q;
                    confirmAnomalousQuantityIfNeeded(
                            qFinal,
                            () -> {
                                remainingQty = qFinal;
                                journal.clear();
                                transitionedTickets.clear();
                                outboundRecipientName = null;
                                pendingTracePo = null;
                                pendingTraceSerial = null;
                                askAssignmentMode();
                            },
                            this::askQuantity);
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> finish())
                .show();
    }

    private void askAssignmentMode() {
        if (remainingQty <= 0) { finishFlow(); return; }

        String title = "📤 Remaining " + remainingQty + " x "
            + com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(pendingProduct.getName());

        String[] options = {
            "🤖 Let Claude decide (auto)",
            "🎫 Choose from ticket list",
            "⌨️ Enter an ID manually",
            "⏭️ Skip - outbound without ticket"
        };

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0: runAiMode();      break;
                        case 1: launchList();     break;
                        case 2: promptManualId(); break;
                        case 3: skipRemaining();  break;
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void launchList() {
        Intent i = new Intent(this, TicketListActivity.class);
        startActivityForResult(i, REQ_TICKET_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQ_TICKET_PICK) return;
        if (resultCode != RESULT_OK || data == null) {
            askAssignmentMode();
            return;
        }
        String ticketId = data.getStringExtra(TicketListActivity.EXTRA_TICKET_ID);
        String summary  = data.getStringExtra(TicketListActivity.EXTRA_TICKET_SUMMARY);
        if (ticketId == null) { askAssignmentMode(); return; }
        askQtyForTicket(ticketId, summary);
    }

    private void promptManualId() {
        final EditText input = new EditText(this);
        input.setHint(R.string.hint_ex_ticket);
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title_ticket_id)
                .setView(input)
                .setPositiveButton(R.string.action_verify, (d, w) -> {
                    String key = input.getText().toString().trim().toUpperCase();
                    if (key.isEmpty()) { askAssignmentMode(); return; }
                    validateAndPickTicket(key);
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> askAssignmentMode())
                .show();
    }

    private void validateAndPickTicket(final String key) {
        setBusy(true, "Verifying " + key + "...");
        JiraReader.getIssue(key, (ticket, err) -> runOnUiThread(() -> {
            setBusy(false, null);
            if (err != null || ticket == null || ticket.key == null) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dlg_title_ticket_not_found)
                        .setMessage(getString(R.string.dlg_msg_ticket_error, key, (err == null ? "not_found" : err)))
                        .setPositiveButton(R.string.action_retry, (d, w) -> promptManualId())
                        .setNegativeButton(R.string.action_back, (d, w) -> askAssignmentMode())
                        .show();
                return;
            }
            askQtyForTicket(ticket.key, ticket.summary);
        }));
    }

    private void askQtyForTicket(final String ticketId, final String summary) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(remainingQty));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_title_assign_to, ticketId))
                .setMessage((summary == null ? "" : summary + "\n\n")
                    + "How many \""
                    + com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(pendingProduct.getName())
                    + "\" for this ticket? (max " + remainingQty + ")")
                .setView(input)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    int q;
                    try { q = Integer.parseInt(input.getText().toString().trim()); }
                    catch (NumberFormatException e) { q = 0; }
                    if (q <= 0 || q > remainingQty) {
                        Toast.makeText(this, R.string.toast_invalid_qty, Toast.LENGTH_SHORT).show();
                        askQtyForTicket(ticketId, summary);
                        return;
                    }
                    final int qFinal = q;
                    confirmAnomalousQuantityIfNeeded(
                            qFinal,
                            () -> ensureRecipientNameThen(() ->
                                    commitAssignment(new Assignment(ticketId, qFinal,
                                            withRecipientReason("Manual selection")))),
                            () -> askQtyForTicket(ticketId, summary));
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> askAssignmentMode())
                .show();
    }

    private void ensureRecipientNameThen(Runnable onDone) {
        if (outboundRecipientName != null && !outboundRecipientName.trim().isEmpty()) {
            onDone.run();
            return;
        }

        final EditText input = new EditText(this);
        input.setHint("e.g.: Thomas Dupont");
        String current = com.example.stockit.util.SessionManager.get(this).getUsername();
        if (current != null && !current.trim().isEmpty()) input.setText(current.trim());

        new AlertDialog.Builder(this)
                .setTitle("Recipient name")
                .setMessage("Who receives this equipment?")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(R.string.action_confirm, (d, w) -> {
                    String n = input.getText() == null ? "" : input.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "Recipient name is required.", Toast.LENGTH_SHORT).show();
                        ensureRecipientNameThen(onDone);
                        return;
                    }
                    outboundRecipientName = n;
                    onDone.run();
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> askAssignmentMode())
                .show();
    }

    private String withRecipientReason(String baseReason) {
        String recipient = outboundRecipientName == null ? "" : outboundRecipientName.trim();
        if (recipient.isEmpty()) return baseReason;
        String b = baseReason == null ? "" : baseReason;
        return b + " | Recipient: " + recipient;
    }

    private void confirmAnomalousQuantityIfNeeded(int requestedQty,
                                                  Runnable onConfirmed,
                                                  Runnable onEdit) {
        controller.getProductMovements(pendingProduct.getId(), movements -> runOnUiThread(() -> {
            String anomalyDetails = buildQtyAnomalyDetails(requestedQty, pendingProduct.getQuantity(), movements);
            if (anomalyDetails == null) {
                onConfirmed.run();
                return;
            }

                String msg = getString(R.string.dlg_msg_qty_anomaly, requestedQty,
                    com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(pendingProduct.getName()))
                    + "\n\n"
                    + anomalyDetails
                    + "\n\n"
                    + getString(R.string.dlg_msg_qty_anomaly_confirm);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_title_qty_anomaly)
                    .setMessage(msg)
                    .setPositiveButton(R.string.action_yes_continue, (d, w) -> onConfirmed.run())
                    .setNegativeButton(R.string.action_edit, (d, w) -> onEdit.run())
                    .setCancelable(false)
                    .show();
        }));
    }

    private String buildQtyAnomalyDetails(int requestedQty,
                                          int availableQty,
                                          List<com.example.stockit.model.StockMovement> movements) {
        int outCount = 0;
        int maxOut = 0;
        double sumOut = 0.0;

        for (com.example.stockit.model.StockMovement m : movements) {
            if (m == null || m.getType() == null || !"OUT".equalsIgnoreCase(m.getType())) continue;
            int q = Math.abs(m.getQuantity());
            if (q <= 0) continue;
            outCount++;
            sumOut += q;
            if (q > maxOut) maxOut = q;
        }

        java.util.List<String> reasons = new java.util.ArrayList<>();
        if (outCount >= 3) {
            int avgOut = (int) Math.round(sumOut / outCount);
            if (avgOut > 0 && requestedQty >= (int) Math.ceil(avgOut * 3.0)) {
                reasons.add(getString(R.string.qty_anomaly_reason_avg, requestedQty, avgOut));
            }
        }
        if (maxOut > 0 && requestedQty > (int) Math.ceil(maxOut * 1.5)) {
            reasons.add(getString(R.string.qty_anomaly_reason_max, requestedQty, maxOut));
        }
        if (availableQty > 0 && requestedQty >= (int) Math.ceil(availableQty * 0.8)) {
            reasons.add(getString(R.string.qty_anomaly_reason_stock_share, requestedQty, availableQty));
        }
        if (outCount == 0 && requestedQty >= 15) {
            reasons.add(getString(R.string.qty_anomaly_reason_no_history, requestedQty));
        }

        if (reasons.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append("- ").append(reasons.get(i));
        }
        return sb.toString();
    }

    private void runAiMode() {
        setBusy(true, "Loading Jira tickets + Claude analysis...");
        JiraReader.loadOpenTickets(50, (tickets, err) -> {
            if (err != null || tickets == null) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    showError("Unable to load tickets: " + (err == null ? "unknown" : err));
                    askAssignmentMode();
                });
                return;
            }

            controller.getFullyFulfilledTicketIds(done -> {
                final List<JiraTicket> filtered = new ArrayList<>();
                for (JiraTicket t : tickets) {
                    if (t == null || t.key == null) continue;
                    if (done != null && done.contains(t.key)) {
                        Log.d(TAG, "Ticket " + t.key + " skipped (already fulfilled)");
                        continue;
                    }
                    filtered.add(t);
                }

                if (filtered.isEmpty()) {
                    runOnUiThread(() -> {
                        setBusy(false, null);
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.dlg_title_no_ticket_ai)
                                .setMessage("No open Jira ticket is available for automatic assignment.")
                                .setPositiveButton("Choose from list", (d, w) -> launchList())
                                .setNegativeButton("Manual entry", (d, w) -> promptManualId())
                                .show();
                    });
                    return;
                }

                TicketMatcher.assign(pendingProduct.getName(), remainingQty, filtered,
                        (assignments, err2) -> runOnUiThread(() -> {
                            setBusy(false, null);
                            if (err2 != null || assignments == null || assignments.isEmpty()) {
                                new AlertDialog.Builder(this)
                                        .setTitle(R.string.dlg_title_ai_no_suggestion)
                                        .setMessage(err2 != null
                                                ? err2
                                                : "Claude found no reliable assignment for this product.")
                                            .setPositiveButton("Choose from list", (d, w) -> launchList())
                                            .setNegativeButton("Manual entry", (d, w) -> promptManualId())
                                        .show();
                                return;
                            }

                            for (TicketMatcher.Assignment a : assignments) {
                                String summary = null;
                                for (JiraTicket jt : filtered) {
                                    if (jt.key != null && jt.key.equals(a.ticketId)) {
                                        summary = jt.summary;
                                        break;
                                    }
                                }
                                controller.saveLlmAnalysis(a.ticketId, pendingProduct.getName(),
                                        summary, a.qty, a.reason);
                            }
                            showAiPreview(assignments);
                        }));
            });
        });
    }

    private void showAiPreview(final List<TicketMatcher.Assignment> assignments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Claude suggests:\n\n");
        int total = 0;
        for (TicketMatcher.Assignment a : assignments) {
            sb.append("- ").append(a.ticketId).append(" -> ").append(a.qty).append("\n");
            sb.append("    ").append(a.reason == null ? "" : a.reason).append("\n\n");
            total += a.qty;
        }
        int rest = remainingQty - total;
        sb.append("Total assigned: ").append(total).append(" / ").append(remainingQty).append("\n");
        if (rest > 0) sb.append("Remaining: ").append(rest).append(" (you can completee this later)");

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title_ai_suggestion)
                .setMessage(sb.toString())
            .setPositiveButton(R.string.action_apply, (d, w) ->
                ensureRecipientNameThen(() -> commitBatchAssignments(assignments)))
                .setNegativeButton(R.string.action_no_other, (d, w) -> askAssignmentMode())
                .show();
    }

    private void commitBatchAssignments(final List<TicketMatcher.Assignment> assignments) {
        commitBatchNext(assignments, 0);
    }

    private void commitBatchNext(final List<TicketMatcher.Assignment> list, final int i) {
        if (i >= list.size()) { askAssignmentMode(); return; }
        TicketMatcher.Assignment a = list.get(i);
        int q = Math.min(a.qty, remainingQty);
        if (q <= 0) { commitBatchNext(list, i + 1); return; }
        final int qFinal = q;
        Assignment mine = new Assignment(a.ticketId, q, a.reason);
        controller.recordExitToTicket(pendingProduct, q, a.ticketId, a.reason,
                () -> {
                    remainingQty -= qFinal;
                    journal.add(mine);
                    controller.recordDeliveryToTicket(a.ticketId, pendingProduct.getName(), qFinal, a.reason);
                    postOutboundCommentToJira(a.ticketId, qFinal, a.reason);
                    transitionTicketToOutOfStockIfNeeded(a.ticketId);
                    markPeripheralsOutOfStockBestEffort(pendingProduct.getName(), qFinal);
                    commitBatchNext(list, i + 1);
                },
                () -> runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.toast_insufficient_stock_for, a.ticketId), Toast.LENGTH_LONG).show();
                    askAssignmentMode();
                }));
    }

    private List<JiraAssetsClient.AssetRecord> pickAssetsForProduct(List<JiraAssetsClient.AssetRecord> assets,
                                                                     String productName,
                                                                     int maxNeeded) {
        String product = normalizeText(productName);
        List<ScoredAsset> scored = new ArrayList<>();
        for (JiraAssetsClient.AssetRecord rec : assets) {
            if (rec == null) continue;
            String status = normalizeText(rec.assetStatus);
            if (!status.isEmpty() && !(status.contains("in stock") || status.contains("stock"))) {
                continue;
            }

            int s = assetScore(product, rec);
            if (s > 0) scored.add(new ScoredAsset(rec, s));
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        List<JiraAssetsClient.AssetRecord> out = new ArrayList<>();
        int take = Math.min(maxNeeded, scored.size());
        for (int i = 0; i < take; i++) out.add(scored.get(i).asset);
        return out;
    }

    private int assetScore(String productName, JiraAssetsClient.AssetRecord rec) {
        String label = normalizeText(rec.label);
        String serial = normalizeText(rec.serialNumber);
        String po = normalizeText(rec.poNumber);
        String traceSn = normalizeText(pendingTraceSerial);
        String tracePo = normalizeText(pendingTracePo);
        int score = 0;

        if (!label.isEmpty()) {
            if (label.contains(productName) || productName.contains(label)) score += 100;
            String[] tokens = productName.split("\\s+");
            for (String t : tokens) {
                if (t.length() < 3) continue;
                if (label.contains(t)) score += 10;
            }
        }
        if (!serial.isEmpty() && serial.contains(productName)) score += 10;

        if (!traceSn.isEmpty() && !serial.isEmpty() && serial.contains(traceSn)) score += 250;
        if (!tracePo.isEmpty() && !po.isEmpty() && po.contains(tracePo)) score += 220;

        return score;
    }

    private void showClaudeAssetsPreview(final List<JiraAssetsClient.AssetRecord> selectedAssets) {
        int qty = Math.min(remainingQty, selectedAssets.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Suggested assets (schema 247, typeId=905):\n\n");
        for (int i = 0; i < qty; i++) {
            JiraAssetsClient.AssetRecord a = selectedAssets.get(i);
            sb.append("- ")
                    .append(a.objectKey == null || a.objectKey.isEmpty() ? a.id : a.objectKey)
                    .append(" | SN: ")
                    .append(a.serialNumber == null || a.serialNumber.isEmpty() ? "-" : a.serialNumber)
                    .append(" | ")
                    .append(a.label == null || a.label.isEmpty() ? "-" : a.label)
                    .append("\n");
        }
        if (remainingQty > qty) {
                sb.append("\nWarning: only ").append(qty)
                    .append(" asset(s) found for ").append(remainingQty).append(" requested.\n")
                    .append("The remaining quantity must be handled with another method.");
        }

        String reason = buildAssetsReason(selectedAssets, qty);
        final int qtyToCommit = qty;

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title_ai_suggestion)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.action_apply,
                        (d, w) -> commitAssignment(new Assignment("NO-TICKET", qtyToCommit, reason)))
                .setNegativeButton(R.string.action_no_other, (d, w) -> askAssignmentMode())
                .show();
    }

    private String buildAssetsReason(List<JiraAssetsClient.AssetRecord> selectedAssets, int qty) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < qty; i++) {
            JiraAssetsClient.AssetRecord a = selectedAssets.get(i);
            String id = (a.objectKey == null || a.objectKey.isEmpty()) ? a.id : a.objectKey;
            String sn = (a.serialNumber == null || a.serialNumber.isEmpty()) ? "-" : a.serialNumber;
            if (i > 0) ids.append(", ");
            ids.append(id).append("/").append(sn);
        }
        return "Claude via Jira Assets 905: " + ids;
    }

    private static final class ScoredAsset {
        final JiraAssetsClient.AssetRecord asset;
        final int score;
        ScoredAsset(JiraAssetsClient.AssetRecord asset, int score) {
            this.asset = asset;
            this.score = score;
        }
    }

    private void commitAssignment(final Assignment a) {
        controller.recordExitToTicket(pendingProduct, a.qty, a.ticketId, a.reason,
                () -> {
                    remainingQty -= a.qty;
                    journal.add(a);
                    controller.recordDeliveryToTicket(a.ticketId, pendingProduct.getName(),
                            a.qty, a.reason);
                postOutboundCommentToJira(a.ticketId, a.qty, a.reason);
                transitionTicketToOutOfStockIfNeeded(a.ticketId);
                markPeripheralsOutOfStockBestEffort(pendingProduct.getName(), a.qty);
                    com.example.stockit.util.AnalyticsHelper.logExitRecorded(
                            getApplicationContext(),
                            deriveExitMode(a.reason),
                            a.qty,
                            a.ticketId != null && !"NO-TICKET".equals(a.ticketId));
                    askAssignmentMode();
                },
                () -> runOnUiThread(() ->
                        Toast.makeText(this, R.string.toast_insufficient_stock, Toast.LENGTH_LONG).show()));
    }

    private void postOutboundCommentToJira(String ticketId, int qty, String reason) {
        if (ticketId == null || ticketId.trim().isEmpty() || "NO-TICKET".equalsIgnoreCase(ticketId)) return;
        String product = com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(pendingProduct.getName());
        String recipient = outboundRecipientName == null ? "" : outboundRecipientName.trim();
        String actor = "";
        com.example.stockit.util.SessionManager sm = com.example.stockit.util.SessionManager.get(this);
        if (sm != null) {
            String username = sm.getUsername();
            String email = sm.getEmail();
            if (username != null && !username.trim().isEmpty()) actor = username.trim();
            else if (email != null && !email.trim().isEmpty()) actor = email.trim();
        }
        StringBuilder comment = new StringBuilder();
        comment.append("StockIT outbound recorded\n");
        comment.append("Product: ").append(product).append("\n");
        comment.append("Quantity: ").append(qty).append("\n");
        if (!recipient.isEmpty()) comment.append("Recipient: ").append(recipient).append("\n");
        if (!actor.isEmpty()) comment.append("Processed by: ").append(actor).append("\n");
        if (reason != null && !reason.trim().isEmpty()) comment.append("Reason: ").append(reason.trim()).append("\n");
        JiraClient.addCommentToIssue(ticketId, comment.toString(), (ok, details) -> {
            if (ok) {
                Log.i(TAG, "Jira comment posted on " + ticketId);
            } else {
                Log.w(TAG, "Unable to post Jira comment on " + ticketId + ": " + details);
            }
        });
    }

    private void transitionTicketToOutOfStockIfNeeded(String ticketId) {
        if (ticketId == null || ticketId.trim().isEmpty() || "NO-TICKET".equalsIgnoreCase(ticketId)) return;
        String key = ticketId.trim().toUpperCase(Locale.ROOT);
        if (transitionedTickets.contains(key)) return;

        transitionedTickets.add(key);
        JiraClient.transitionIssueToStatus(key, "Inactive", (ok, details) -> {
            if (ok) {
                Log.i(TAG, "Jira ticket moved to Inactive: " + key);
                return;
            }

            transitionedTickets.remove(key);
            Log.w(TAG, "Unable to transition Jira ticket " + key
                    + " to Inactive: " + details);
        });
    }

    private void markPeripheralsOutOfStockBestEffort(String productName, int qty) {
        if (qty <= 0 || productName == null || productName.trim().isEmpty()) return;

        String assignedUser = "";
        String assignedEmail = "";

        if (outboundRecipientName != null && !outboundRecipientName.trim().isEmpty()) {
            assignedUser = outboundRecipientName.trim();
        } else {
            com.example.stockit.util.SessionManager sm = com.example.stockit.util.SessionManager.get(this);
            if (sm != null) {
                String u = sm.getUsername();
                String e = sm.getEmail();
                if (u != null && !u.trim().isEmpty()) assignedUser = u.trim();
                if (e != null && !e.trim().isEmpty()) assignedEmail = e.trim();
                if (assignedUser.isEmpty() && !assignedEmail.isEmpty()) assignedUser = assignedEmail;
            }
        }

        final String assignedUserFinal = assignedUser;
        final String assignedEmailFinal = assignedEmail;

        JiraAssetsClient.searchAssetsByAql("objectTypeId = 940", 300, (assets, err) -> {
            if (err != null || assets == null || assets.isEmpty()) {
                Log.w(TAG, "Assets search unavailable for stock-out sync: " + (err == null ? "empty" : err));
                return;
            }

            List<JiraAssetsClient.AssetRecord> candidates = pickAssetsForProduct(assets, productName, qty * 3);
            if (candidates.isEmpty()) {
                Log.i(TAG, "No peripheral candidate found for Inactive sync: " + productName);
                return;
            }

            Set<String> ids = new HashSet<>();
            List<String> objectIds = new ArrayList<>();
            for (JiraAssetsClient.AssetRecord rec : candidates) {
                if (objectIds.size() >= qty) break;
                if (rec == null || rec.id == null || rec.id.trim().isEmpty()) continue;
                String st = normalizeText(rec.assetStatus);
                if (st.contains("out of stock") || st.contains("inactive")) continue;

                String recSn = normalizeText(rec.serialNumber);
                String recPo = normalizeText(rec.poNumber);
                String traceSn = normalizeText(pendingTraceSerial);
                String tracePo = normalizeText(pendingTracePo);
                if (!traceSn.isEmpty() && (recSn.isEmpty() || !recSn.contains(traceSn))) continue;
                if (!tracePo.isEmpty() && (recPo.isEmpty() || !recPo.contains(tracePo))) continue;

                if (!ids.add(rec.id)) continue;
                objectIds.add(rec.id);
            }

            if (objectIds.isEmpty()) return;

        JiraAssetsClient.updateAssetStatusAndAssigneeByIds(940, objectIds, "Inactive",
                assignedUserFinal, assignedEmailFinal,
                    (ok, ko, sample) -> {
                        if (ko > 0) {
                            Log.w(TAG, "Assets status sync partial (Inactive): " + ok + " ok / " + ko + " ko - " + sample);
                            runOnUiThread(() -> Toast.makeText(
                                    this,
                                    "Warning: Jira Assets: " + ok + " updated, " + ko + " failed",
                                    Toast.LENGTH_LONG).show());
                        } else {
                            Log.i(TAG, "Assets status sync done: " + ok + " object(s) set to Inactive");
                            runOnUiThread(() -> Toast.makeText(
                                    this,
                                    "Jira Assets: " + ok + " asset(s) moved to Inactive and assigned",
                                    Toast.LENGTH_SHORT).show());
                        }
                    });
        });
    }

    private static String deriveExitMode(String reason) {
        if (reason == null) return "unknown";
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        if (r.contains("manual"))   return "manual";
        if (r.contains("free"))     return "no_ticket";
        if (r.contains("list"))     return "list";
        return "ai_claude";
    }

    private void skipRemaining() {
        final int qtyToRelease = remainingQty;
        controller.recordExitToTicket(pendingProduct, remainingQty, "NO-TICKET", "Free outbound",
                () -> {
                    journal.add(new Assignment("NO-TICKET", qtyToRelease, "Free outbound"));
                    markPeripheralsOutOfStockBestEffort(pendingProduct.getName(), qtyToRelease);
                    remainingQty = 0;
                    finishFlow();
                },
                () -> runOnUiThread(() -> Toast.makeText(this, R.string.toast_insufficient_stock, Toast.LENGTH_LONG).show()));
    }

    private void finishFlow() {
        int totalOut = 0;
        StringBuilder recap = new StringBuilder();
        String displayName = com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(pendingProduct.getName());
        recap.append("Equipment: ").append(displayName).append("\n\n");
        for (Assignment a : journal) {
            recap.append("- ").append(a.ticketId).append(" -> ").append(a.qty).append("\n");
            if (a.reason != null && !a.reason.isEmpty())
                recap.append("    ").append(a.reason).append("\n");
            totalOut += a.qty;
        }
        recap.append("\nTotal released: ").append(totalOut);

        SlackNotifier.send(String.format(Locale.US,
            ":package: [StockIT] Outbound of %d x %s to %d ticket(s)",
                totalOut, displayName, journal.size()));

        NotificationHelper.showNotification(this, "StockIT - Outbound recorded",
                totalOut + " x " + displayName,
                (int) System.currentTimeMillis());

        com.example.stockit.util.StockItReporter.sendEvent(this,
            "Stock outbound: " + displayName,
            "A stock outbound operation was recorded.\n\n"
                + "- Product         : " + displayName + "\n"
                + "- Total quantity  : " + totalOut + "\n"
                + "- Tickets served  : " + journal.size() + "\n\n"
                + "Ticket details:\n" + recap,
                "wissem.soussia@vista.com");

        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title_exit_ok)
                .setMessage(recap.toString())
                .setPositiveButton(R.string.action_ok, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void showError(String msg) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title_exit_error)
                .setMessage(msg)
                .setPositiveButton(R.string.action_ok, null)
                .show();
    }

    private void setBusy(boolean busy, String label) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnCapture.setEnabled(!busy);
        if (label != null) status.setText(label);
    }

    private static byte[] toJpegBytes(ImageProxy image) {
        ByteBuffer buf = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    private static class Assignment {
        final String ticketId; final int qty; final String reason;
        Assignment(String i, int q, String r) { ticketId = i; qty = q; reason = r; }
    }
}
