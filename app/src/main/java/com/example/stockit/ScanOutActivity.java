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
import com.example.stockit.util.JiraReader;
import com.example.stockit.util.SlackNotifier;
import com.example.stockit.util.TicketMatcher;
import com.example.stockit.util.SharedBridge;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * StockIT PFE — Sortie d'équipement de stock, rattachée à un ou plusieurs tickets Jira.
 *
 * Flow :
 *   1) Scan équipement (Claude Opus 4 vision)
 *   2) Match en base : chercher un Product dont le nom contient l'objet détecté
 *      → check stock disponible
 *   3) Saisie quantité (EditText numérique)
 *   4) Boucle d'assignment tant que qty > 0 :
 *        Dialog 3 modes : liste Jira / manuel / IA
 *        → chaque assignment : dialog de confirmation → recordExitToTicket → décrément qty
 *   5) Récap final + Slack + notif
 */
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

    // État progressif du workflow
    private Product pendingProduct;      // Product en base qui matche
    private int     remainingQty;        // qty restant à assigner
    private final List<Assignment> journal = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan_out);

        preview   = findViewById(R.id.soPreview);
        status    = findViewById(R.id.soStatus);
        progress  = findViewById(R.id.soProgress);
        btnCapture= findViewById(R.id.soBtnCapture);
        Button btnCancel = findViewById(R.id.soBtnCancel);

        controller = new MainController(this);

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
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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

    // ============ 1) Capture + Claude vision ============
    private void capture() {
        if (imageCapture == null) return;
        setBusy(true, "🧠 Analyse par Claude…");
        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                        byte[] jpeg = toJpegBytes(image);
                        image.close();
                        analyze(jpeg);
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        setBusy(false, "❌ Capture : " + e.getMessage());
                    }
                });
    }

    private static final int REQ_CONFIRM = 6264;
    /** Ticket → demandeur (reporter Jira), pour « Assigned User » dans Jira Assets. */
    private final java.util.Map<String, String> reporters = new java.util.HashMap<>();
    private final List<String> assetNotes = new ArrayList<>();

    private void analyze(byte[] jpeg) {
        SharedBridge.identify(jpeg, (name, confidence, serial, error) -> {
            if (name != null && !name.isEmpty()) {
                openConfirm(name, confidence, null);
            } else {
                GeminiGatewayClient.identify(jpeg, (n2, e2) -> runOnUiThread(() -> {
                    if (n2 != null && !n2.isEmpty()) openConfirm(n2, -1, null);
                    else openConfirm(null, -1, e2 != null ? e2 : error);
                }));
            }
        });
    }

    /** Confirmation (produit existant obligatoire), identique iOS ConfirmAssetView(requireExisting: true). */
    private void openConfirm(String name, int confidence, String error) {
        setBusy(false, name != null ? "🧠 Détecté : " + name + (confidence >= 0 ? " (" + confidence + " %)" : "")
                : "❌ Analyse impossible : " + (error != null ? error : "réponse vide"));
        Intent i = new Intent(this, ConfirmAssetActivity.class);
        i.putExtra(ConfirmAssetActivity.EXTRA_DETECTED, name);
        i.putExtra(ConfirmAssetActivity.EXTRA_ERROR, error);
        i.putExtra(ConfirmAssetActivity.EXTRA_CONFIDENCE, confidence);
        i.putExtra(ConfirmAssetActivity.EXTRA_REQUIRE_EXISTING, true);
        startActivityForResult(i, REQ_CONFIRM);
        pendingDetected = name;
        pendingConfidence = confidence;
    }

    private String pendingDetected;
    private int pendingConfidence = -1;

    private void onConfirmed(int productId, String finalName) {
        controller.recordVisionFeedback(pendingDetected, finalName, pendingConfidence, false);
        controller.getStock(products -> runOnUiThread(() -> {
            Product match = null;
            for (Product p : products) if (p.getId() == productId) { match = p; break; }
            if (match == null || match.getQuantity() <= 0) {
                new AlertDialog.Builder(this)
                        .setTitle("❌ Stock épuisé")
                        .setMessage("Aucun \"" + finalName + "\" disponible en stock (quantité = 0).")
                        .setPositiveButton("OK", (d, w) -> finish())
                        .show();
                return;
            }
            pendingProduct = match;
            askQuantity();
        }));
    }

    // ============ 3) Saisie quantité ============
    private void askQuantity() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("ex: 5");

        new AlertDialog.Builder(this)
                .setTitle("Combien de \"" + pendingProduct.getName() + "\" sortir ?")
                .setMessage("Stock disponible : " + pendingProduct.getQuantity())
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Continuer", (d, w) -> {
                    int q;
                    try { q = Integer.parseInt(input.getText().toString().trim()); }
                    catch (NumberFormatException e) { q = 0; }
                    if (q <= 0) {
                        Toast.makeText(this, "Quantité invalide", Toast.LENGTH_SHORT).show();
                        askQuantity();
                        return;
                    }
                    if (q > pendingProduct.getQuantity()) {
                        Toast.makeText(this,
                                "Stock insuffisant (dispo: " + pendingProduct.getQuantity() + ")",
                                Toast.LENGTH_LONG).show();
                        askQuantity();
                        return;
                    }
                    remainingQty = q;
                    journal.clear();
                    askAssignmentMode();
                })
                .setNegativeButton("Annuler", (d, w) -> finish())
                .show();
    }

    // ============ 4) Dialog 3 modes (boucle jusqu'à qty = 0) ============
    private void askAssignmentMode() {
        if (remainingQty <= 0) { finishFlow(); return; }

        String title = "Reste " + remainingQty + " × " + pendingProduct.getName();

        String[] options = {
                "🧠 Laisser Claude décider (auto)",
                "🔎 Choisir dans la liste des tickets",
                "✏️ Taper un ID manuellement",
                "⏭️ Skip — sortir sans ticket"
        };

        // NB : setMessage + setItems sont incompatibles dans AlertDialog
        // (le message masque la liste). On met le contexte dans le titre.
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

    // ---- Mode A : liste Jira ----
    private void launchList() {
        Intent i = new Intent(this, TicketListActivity.class);
        startActivityForResult(i, REQ_TICKET_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONFIRM) {
            if (resultCode == RESULT_OK && data != null) {
                onConfirmed(data.getIntExtra(ConfirmAssetActivity.EXTRA_PRODUCT_ID, -1),
                        data.getStringExtra(ConfirmAssetActivity.EXTRA_NAME));
            } else {
                status.setText("Pointez la caméra vers l'équipement à sortir puis capturez.");
            }
            return;
        }
        if (requestCode != REQ_TICKET_PICK) return;
        if (resultCode != RESULT_OK || data == null) {
            askAssignmentMode();
            return;
        }
        String ticketId = data.getStringExtra(TicketListActivity.EXTRA_TICKET_ID);
        String summary  = data.getStringExtra(TicketListActivity.EXTRA_TICKET_SUMMARY);
        String reporter = data.getStringExtra(TicketListActivity.EXTRA_TICKET_REPORTER);
        if (ticketId == null) { askAssignmentMode(); return; }
        if (reporter != null && !reporter.isEmpty()) reporters.put(ticketId, reporter);
        askQtyForTicket(ticketId, summary);
    }

    // ---- Mode B : ID manuel ----
    private void promptManualId() {
        final EditText input = new EditText(this);
        input.setHint("ex: ETXTUN-42");
        new AlertDialog.Builder(this)
                .setTitle("Saisis l'ID du ticket")
                .setView(input)
                .setPositiveButton("Vérifier", (d, w) -> {
                    String key = input.getText().toString().trim().toUpperCase();
                    if (key.isEmpty()) { askAssignmentMode(); return; }
                    validateAndPickTicket(key);
                })
                .setNegativeButton("Annuler", (d, w) -> askAssignmentMode())
                .show();
    }

    private void validateAndPickTicket(final String key) {
        setBusy(true, "⏳ Vérification " + key + "…");
        JiraReader.getIssue(key, (ticket, err) -> runOnUiThread(() -> {
            setBusy(false, null);
            if (err != null || ticket == null || ticket.key == null) {
                new AlertDialog.Builder(this)
                        .setTitle("❌ Ticket introuvable")
                        .setMessage("Impossible de trouver " + key + " (" + (err == null ? "not_found" : err) + ")")
                        .setPositiveButton("Réessayer", (d, w) -> promptManualId())
                        .setNegativeButton("Retour", (d, w) -> askAssignmentMode())
                        .show();
                return;
            }
            if (ticket.reporter != null && !ticket.reporter.isEmpty()) reporters.put(ticket.key, ticket.reporter);
            askQtyForTicket(ticket.key, ticket.summary);
        }));
    }

    // ---- Sous-dialog : combien assigner à ce ticket précis ----
    private void askQtyForTicket(final String ticketId, final String summary) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(remainingQty));
        new AlertDialog.Builder(this)
                .setTitle("Assigner à " + ticketId)
                .setMessage((summary == null ? "" : summary + "\n\n")
                        + "Combien de \"" + pendingProduct.getName()
                        + "\" pour ce ticket ? (max " + remainingQty + ")")
                .setView(input)
                .setPositiveButton("Confirmer", (d, w) -> {
                    int q;
                    try { q = Integer.parseInt(input.getText().toString().trim()); }
                    catch (NumberFormatException e) { q = 0; }
                    if (q <= 0 || q > remainingQty) {
                        Toast.makeText(this, "Quantité invalide", Toast.LENGTH_SHORT).show();
                        askQtyForTicket(ticketId, summary);
                        return;
                    }
                    commitAssignment(new Assignment(ticketId, q, "Choix manuel"));
                })
                .setNegativeButton("Annuler", (d, w) -> askAssignmentMode())
                .show();
    }

    // ---- Mode C : IA ----
    private void runAiMode() {
        setBusy(true, "🧠 Chargement tickets Jira + analyse Claude…");
        JiraReader.loadOpenTickets(50, (tickets, err) -> {
            if (err != null || tickets == null) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    showError("Impossible de charger les tickets : " + err);
                    askAssignmentMode();
                });
                return;
            }
            // StockIT PFE : filtrer les tickets déjà entièrement livrés (cache local)
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
                                .setTitle("🧠 Aucun ticket à analyser")
                                .setMessage("Tous les tickets Jira ouverts ont déjà été traités "
                                        + "(cache local). Utilise un autre mode.")
                                .setPositiveButton("OK", (d, w) -> askAssignmentMode())
                                .show();
                    });
                    return;
                }
                final java.util.Set<String> known = new java.util.HashSet<>();
                for (JiraTicket t : filtered) {
                    known.add(t.key);
                    if (t.reporter != null && !t.reporter.isEmpty()) reporters.put(t.key, t.reporter);
                }
                SharedBridge.assignTickets(pendingProduct.getName(), remainingQty, filtered, (shared, err2) -> {
                    setBusy(false, null);
                    // Garde-fou : uniquement des tickets réellement envoyés à Claude (pas d'ID inventé).
                    List<TicketMatcher.Assignment> assignments = new ArrayList<>();
                    List<Integer> confidences = new ArrayList<>();
                    if (shared != null) for (com.example.stockit.shared.ai.Assignment a : shared) {
                        if (!known.contains(a.getTicketId())) continue;
                        assignments.add(new TicketMatcher.Assignment(a.getTicketId(), a.getQty(), a.getReason()));
                        confidences.add(a.getConfidence());
                    }
                    if (assignments.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("🧠 Claude n'a rien proposé")
                                .setMessage(err2 != null ? err2 : "Aucun ticket ne correspond à cet équipement.")
                                .setPositiveButton("Autre méthode", (d, w) -> askAssignmentMode())
                                .show();
                        return;
                    }
                    for (TicketMatcher.Assignment a : assignments) {
                        String summary = null;
                        for (JiraTicket jt : filtered) {
                            if (jt.key != null && jt.key.equals(a.ticketId)) { summary = jt.summary; break; }
                        }
                        controller.saveLlmAnalysis(a.ticketId, pendingProduct.getName(), summary, a.qty, a.reason);
                    }
                    boolean lowConf = false;
                    for (int c : confidences) if (c >= 0 && c < 60) { lowConf = true; break; }
                    if (lowConf) showReview(assignments, confidences);
                    else showAiPreview(assignments, confidences);
                });
            });
        });
    }

    /** Confiance faible sur au moins un ticket → revue ticket par ticket (iOS SuggestionReviewSheet). */
    private void showReview(final List<TicketMatcher.Assignment> assignments, final List<Integer> conf) {
        String[] labels = new String[assignments.size()];
        boolean[] checked = new boolean[assignments.size()];
        for (int i = 0; i < assignments.size(); i++) {
            TicketMatcher.Assignment a = assignments.get(i);
            int c = conf.get(i);
            labels[i] = a.ticketId + " → " + a.qty + (c >= 0 ? "  (" + ConfirmAssetActivity.confidenceLabel(c) + ")" : "")
                    + "\n" + (a.reason == null ? "" : a.reason);
            checked[i] = c >= 60;
        }
        new AlertDialog.Builder(this)
                .setTitle("🧠 Claude n'est pas sûr — coche ce que tu valides")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Appliquer la sélection", (d, w) -> {
                    List<TicketMatcher.Assignment> accepted = new ArrayList<>();
                    for (int i = 0; i < assignments.size(); i++) if (checked[i]) accepted.add(assignments.get(i));
                    if (accepted.isEmpty()) askAssignmentMode(); else commitBatchAssignments(accepted);
                })
                .setNegativeButton("Autre méthode", (d, w) -> askAssignmentMode())
                .setCancelable(false)
                .show();
    }

    private void showAiPreview(final List<TicketMatcher.Assignment> assignments, final List<Integer> conf) {
        StringBuilder sb = new StringBuilder();
        sb.append("Claude propose :\n\n");
        int total = 0;
        for (int i = 0; i < assignments.size(); i++) {
            TicketMatcher.Assignment a = assignments.get(i);
            int c = conf.get(i);
            sb.append("• ").append(a.ticketId).append(" → ").append(a.qty)
              .append(c >= 0 ? "  (confiance " + c + " %)" : "").append("\n");
            sb.append("    ").append(a.reason == null ? "" : a.reason).append("\n\n");
            total += a.qty;
        }
        int rest = remainingQty - total;
        sb.append("Total assigné : ").append(total).append(" / ").append(remainingQty).append("\n");
        if (rest > 0) sb.append("Restant : ").append(rest).append(" (tu choisiras la méthode ensuite)");

        new AlertDialog.Builder(this)
                .setTitle("🧠 Suggestion IA")
                .setMessage(sb.toString())
                .setPositiveButton("Appliquer", (d, w) -> commitBatchAssignments(assignments))
                .setNegativeButton("Non, autre choix", (d, w) -> askAssignmentMode())
                .show();
    }

    // ---- Commit ----
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
                    controller.recordDeliveryToTicket(a.ticketId, pendingProduct.getName(),
                            qFinal, a.reason);
                    syncAssets(a.ticketId, qFinal, a.reason, () -> commitBatchNext(list, i + 1));
                },
                () -> runOnUiThread(() -> {
                    Toast.makeText(this, "Stock insuffisant pour " + a.ticketId, Toast.LENGTH_LONG).show();
                    askAssignmentMode();
                }));
    }

    private void commitAssignment(final Assignment a) {
        controller.recordExitToTicket(pendingProduct, a.qty, a.ticketId, a.reason,
                () -> {
                    remainingQty -= a.qty;
                    journal.add(a);
                    controller.recordDeliveryToTicket(a.ticketId, pendingProduct.getName(),
                            a.qty, a.reason);
                    syncAssets(a.ticketId, a.qty, a.reason, this::askAssignmentMode);
                },
                () -> runOnUiThread(() ->
                        Toast.makeText(this, "Stock insuffisant", Toast.LENGTH_LONG).show()));
    }

    private void skipRemaining() {
        // Sortie libre (sans ticket)
        controller.recordExitToTicket(pendingProduct, remainingQty, "NO-TICKET", "Sortie libre",
                () -> {
                    final int q = remainingQty;
                    journal.add(new Assignment("NO-TICKET", q, "Sortie libre"));
                    remainingQty = 0;
                    syncAssets("NO-TICKET", q, "Sortie libre", this::finishFlow);
                },
                () -> runOnUiThread(() -> Toast.makeText(this, "Stock insuffisant", Toast.LENGTH_LONG).show()));
    }

    /** Jira Assets : objets « In stock » → « OUT / Checked Out » + commentaire sur le ticket (iOS). */
    private void syncAssets(String ticket, int qty, String reason, Runnable next) {
        setBusy(true, "⏳ Mise à jour Jira Assets…");
        com.example.stockit.model.User u = MainController.getCurrentUser();
        String actor = u != null ? u.getUsername() : "stockit";
        SharedBridge.assetsCheckOut(pendingProduct.getName(), qty, ticket, reporters.get(ticket), reason, actor, (ok, detail) -> {
            setBusy(false, null);
            controller.audit(ok ? "ASSETS_CHECK_OUT" : "ASSETS_CHECK_OUT_FAILED",
                    pendingProduct.getName() + " x" + qty + " (" + ticket + ") → " + (detail != null ? detail : ""));
            if (!"assets_not_configured".equals(detail)) {
                assetNotes.add(ok ? ticket + " : " + detail : ticket + " : ⚠️ " + (detail != null ? detail : "échec"));
            }
            next.run();
        });
    }

    // ============ 5) Récap final ============
    private void finishFlow() {
        int totalOut = 0;
        StringBuilder recap = new StringBuilder();
        recap.append("Équipement : ").append(pendingProduct.getName()).append("\n\n");
        for (Assignment a : journal) {
            recap.append("• ").append(a.ticketId).append(" → ").append(a.qty).append("\n");
            if (a.reason != null && !a.reason.isEmpty())
                recap.append("    ").append(a.reason).append("\n");
            totalOut += a.qty;
        }
        if (!assetNotes.isEmpty()) {
            recap.append("\nJira Assets (OUT) :\n");
            for (String n : assetNotes) recap.append("• ").append(n).append("\n");
        }
        recap.append("\nTotal sorti : ").append(totalOut);
        if (SharedBridge.isTestMode(this)) recap.append("\n\n(Mode test : pas de Slack / e-mail / Jira réels)");
        StockWidget.refreshAll(this);

        SlackNotifier.send(String.format(Locale.US,
                ":package: [StockIT] Sortie de %d x %s vers %d ticket(s)",
                totalOut, pendingProduct.getName(), journal.size()));

        NotificationHelper.showNotification(this, "StockIT — Sortie enregistrée",
                totalOut + " x " + pendingProduct.getName(),
                (int) System.currentTimeMillis());

        com.example.stockit.util.StockItReporter.sendEvent(this,
                "Sortie de stock : " + pendingProduct.getName(),
                "📤 Une sortie de stock vient d'être enregistrée.\n\n"
                        + "• Produit         : " + pendingProduct.getName() + "\n"
                        + "• Quantité totale : " + totalOut + "\n"
                        + "• Tickets servis  : " + journal.size() + "\n\n"
                        + "Détail par ticket :\n" + recap,
                com.example.stockit.BuildConfig.STOCKIT_DEFAULT_EMAIL);

        new AlertDialog.Builder(this)
                .setTitle("🟢 Sortie enregistrée")
                .setMessage(recap.toString())
                .setPositiveButton("OK", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ============ helpers ============
    private void showError(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Sortie équipement")
                .setMessage(msg)
                .setPositiveButton("OK", null)
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

    /** Structure locale d'un assignment pour le journal. */
    private static class Assignment {
        final String ticketId; final int qty; final String reason;
        Assignment(String i, int q, String r) { ticketId = i; qty = q; reason = r; }
    }
}
