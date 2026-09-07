package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.controller.MainController;
import com.example.stockit.controller.POBlockAdapter;
import com.example.stockit.model.PurchaseOrder;
import com.example.stockit.util.DeliveryNoteParser;
import com.example.stockit.util.DeliveryNoteParser.POBlock;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * StockIT PFE — Écran de sélection d'un PO parmi ceux extraits d'une facture.
 *
 * Entrée (Intent extras) :
 *   - EXTRA_EQUIPMENT_NAME (String) : ex "Écran" — nom détecté par Claude
 *   - EXTRA_PARSED_NOTE    (Serializable) : {@link DeliveryNoteParser.ParsedNote}
 *
 * Sortie (setResult) :
 *   - EXTRA_SELECTED_NUMBER      (String) : ex "PO-5678"
 *   - EXTRA_SELECTED_DESCRIPTION (String) : description Claude du PO choisi
 *   - EXTRA_SELECTED_SUPPLIER    (String) : fournisseur extrait de la facture
 *
 * Politique (B) : si le PO choisi ne mentionne pas l'équipement scanné dans sa description,
 * on affiche un warning non bloquant. L'utilisateur peut forcer.
 */
public class POSelectionActivity extends AppCompatActivity {

    public static final String EXTRA_EQUIPMENT_NAME      = "equipment_name";
    public static final String EXTRA_PARSED_NOTE         = "parsed_note";
    public static final String EXTRA_SELECTED_NUMBER     = "selected_po_number";
    public static final String EXTRA_SELECTED_DESCRIPTION= "selected_po_description";
    public static final String EXTRA_SELECTED_SUPPLIER   = "selected_po_supplier";

    private TextView title, subtitle;
    private RecyclerView recycler;
    private Button btnConfirm, btnCancel;

    private String equipmentName;
    private DeliveryNoteParser.ParsedNote note;
    private POBlockAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_po_selection);

        title      = findViewById(R.id.poSelTitle);
        subtitle   = findViewById(R.id.poSelSubtitle);
        recycler   = findViewById(R.id.poSelRecycler);
        btnConfirm = findViewById(R.id.poSelBtnConfirm);
        btnCancel  = findViewById(R.id.poSelBtnCancel);

        equipmentName = getIntent().getStringExtra(EXTRA_EQUIPMENT_NAME);
        note = (DeliveryNoteParser.ParsedNote) getIntent().getSerializableExtra(EXTRA_PARSED_NOTE);

        if (note == null || note.purchaseOrders == null || note.purchaseOrders.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("❌ Aucun PO détecté")
                    .setMessage("La facture n'a produit aucun numéro de PO exploitable."
                            + (note != null && note.rawOcr != null ? "\n\nOCR:\n" + trim(note.rawOcr, 500) : ""))
                    .setPositiveButton("OK", (d, w) -> { setResult(RESULT_CANCELED); finish(); })
                    .setCancelable(false)
                    .show();
            return;
        }

        title.setText(note.purchaseOrders.size() + " PO détecté(s) — sélectionne le bon");
        subtitle.setText("🖥️ Équipement scanné : " + (equipmentName == null ? "?" : equipmentName)
                + (note.supplier != null ? "   •   Fournisseur facture : " + note.supplier : ""));

        setupRecycler();

        btnCancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        btnConfirm.setOnClickListener(v -> tryConfirm());
    }

    private void setupRecycler() {
        // Récupère les ids de PO existants en base (pour badge "✅ En base")
        MainController controller = new MainController(this);
        controller.getPurchaseOrders(orders -> {
            Set<Integer> known = new HashSet<>();
            if (orders != null) for (PurchaseOrder po : orders) known.add(po.getId());
            adapter = new POBlockAdapter(note.purchaseOrders, known, equipmentName,
                    (block, pos) -> btnConfirm.setEnabled(true));
            recycler.setLayoutManager(new LinearLayoutManager(this));
            recycler.setAdapter(adapter);
        });
    }

    private void tryConfirm() {
        POBlock selected = adapter == null ? null : adapter.getSelected();
        if (selected == null) return;

        boolean semanticMatch = matchesEquipment(selected);
        if (semanticMatch) {
            returnResult(selected);
            return;
        }
        // Option B : warning non bloquant
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Incohérence possible")
                .setMessage("Tu as scanné : « " + (equipmentName == null ? "?" : equipmentName) + " ».\n\n"
                        + "Mais le PO " + selected.number + " indique :\n« "
                        + (selected.description == null ? "(sans description)" : selected.description) + " »\n\n"
                        + "Ce PO ne mentionne pas ton équipement.\n"
                        + "Confirmer quand même ce rattachement ?")
                .setPositiveButton("Oui, rattacher", (d, w) -> returnResult(selected))
                .setNegativeButton("Non, choisir un autre", null)
                .show();
    }

    private boolean matchesEquipment(@NonNull POBlock block) {
        if (equipmentName == null || equipmentName.isEmpty()) return true;
        if (block.description == null) return false;
        String needle = equipmentName.toLowerCase();
        String hay = block.description.toLowerCase();
        return hay.contains(needle);
    }

    private void returnResult(@NonNull POBlock selected) {
        Intent data = new Intent();
        data.putExtra(EXTRA_SELECTED_NUMBER,      selected.number);
        data.putExtra(EXTRA_SELECTED_DESCRIPTION, selected.description);
        data.putExtra(EXTRA_SELECTED_SUPPLIER,    note != null ? note.supplier : null);
        setResult(RESULT_OK, data);
        finish();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
