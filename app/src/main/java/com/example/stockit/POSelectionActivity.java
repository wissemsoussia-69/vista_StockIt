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

public class POSelectionActivity extends AppCompatActivity {

    public static final String EXTRA_EQUIPMENT_NAME      = "equipment_name";
    public static final String EXTRA_PARSED_NOTE         = "parsed_note";
    public static final String EXTRA_SELECTED_NUMBER     = "selected_po_number";
    public static final String EXTRA_SELECTED_DESCRIPTION= "selected_po_description";
    public static final String EXTRA_SELECTED_SUPPLIER   = "selected_po_supplier";
    public static final String EXTRA_SELECTED_BRAND      = "selected_po_brand";
    public static final String EXTRA_SELECTED_MODEL      = "selected_po_model";
    public static final String EXTRA_SELECTED_SERIALS    = "selected_po_serials";
    public static final String EXTRA_INVOICE_NUMBER      = "invoice_number";
    public static final String EXTRA_INVOICE_DATE        = "invoice_date";

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
                    .setTitle(R.string.dlg_title_no_po)
                    .setMessage(getString(R.string.dlg_msg_invoice_no_po)
                            + (note != null && note.rawOcr != null ? "\n\nOCR:\n" + trim(note.rawOcr, 500) : ""))
                    .setPositiveButton(R.string.action_ok, (d, w) -> { setResult(RESULT_CANCELED); finish(); })
                    .setCancelable(false)
                    .show();
            return;
        }

        title.setText(note.purchaseOrders.size() + " PO detected - select the correct one");
        subtitle.setText("Scanned equipment: " + (equipmentName == null ? "?" : equipmentName)
            + (note.supplier != null ? " | Invoice supplier: " + note.supplier : ""));

        setupRecycler();

        btnCancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        btnConfirm.setOnClickListener(v -> tryConfirm());
    }

    private void setupRecycler() {
        MainController controller = MainController.getInstance(this);
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title_po_inconsistency)
                .setMessage(getString(R.string.dlg_msg_po_inconsistency,
                    equipmentName == null ? "?" : equipmentName,
                    selected.number,
                    selected.description == null ? "(no description)" : selected.description))
                .setPositiveButton(R.string.action_yes_attach, (d, w) -> returnResult(selected))
                .setNegativeButton(R.string.action_no_pick_another, null)
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
        data.putExtra(EXTRA_SELECTED_BRAND, selected.brand);
        data.putExtra(EXTRA_SELECTED_MODEL, selected.model);
        if (selected.serialNumbers != null && !selected.serialNumbers.isEmpty()) {
            data.putStringArrayListExtra(EXTRA_SELECTED_SERIALS,
                    new java.util.ArrayList<>(selected.serialNumbers));
        }
        if (note != null) {
            data.putExtra(EXTRA_INVOICE_NUMBER, note.invoiceNumber);
            data.putExtra(EXTRA_INVOICE_DATE,   note.invoiceDate);
        }
        setResult(RESULT_OK, data);
        finish();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
