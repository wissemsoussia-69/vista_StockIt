package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import com.example.stockit.controller.MainController;
import com.example.stockit.model.Product;
import com.example.stockit.util.SharedBridge;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Confirmation après analyse IA (identique iOS ConfirmAssetView) :
 * nom proposé par Claude (modifiable), badge de confiance, case « J'ai vérifié »
 * obligatoire sous 60 %, numéro de série (entrée), et rattachement à un produit
 * existant (suggestions accent-insensibles + synonymes FR/EN).
 *
 * Résultat : EXTRA_NAME, EXTRA_PRODUCT_ID (-1 si nouveau), EXTRA_SERIAL.
 * RESULT_CANCELED = « Reprendre la photo ».
 */
public class ConfirmAssetActivity extends SimpleScreenActivity {

    public static final String EXTRA_DETECTED = "detected";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_REQUIRE_EXISTING = "require_existing";
    public static final String EXTRA_CONFIDENCE = "confidence";
    public static final String EXTRA_SERIAL = "serial";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PRODUCT_ID = "product_id";

    private String detected;
    private boolean requireExisting;
    private int confidence;
    private boolean needsCheck;

    private TextInputEditText nameField, serialField, searchField;
    private MaterialCheckBox verifiedBox;
    private LinearLayout listBox;
    private List<Product> all = new ArrayList<>();
    private List<Product> candidates = new ArrayList<>();
    private Product selected;
    private boolean settingName = false;

    @Override protected String screenTitle() { return "Confirmer l'équipement"; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        detected = in.getStringExtra(EXTRA_DETECTED);
        String error = in.getStringExtra(EXTRA_ERROR);
        requireExisting = in.getBooleanExtra(EXTRA_REQUIRE_EXISTING, false);
        confidence = in.getIntExtra(EXTRA_CONFIDENCE, -1);
        String serial = in.getStringExtra(EXTRA_SERIAL);
        needsCheck = confidence >= 0 && confidence < 60;

        if (detected != null && !detected.isEmpty()) {
            LinearLayout s = section("Claude a identifié");
            text(s, "✨ " + detected, 17, true, R.color.vista_navy);
            TextView badge = text(s, confidenceLabel(confidence), 13, true, R.color.vista_navy);
            badge.setTextColor(confidenceColor(confidence));
            if (needsCheck) {
                text(s, "⚠️ Confiance faible : vérifie le nom (et le produit) avant de valider.", 12, false, R.color.vista_coral);
                verifiedBox = new MaterialCheckBox(this);
                verifiedBox.setText("J'ai vérifié l'équipement");
                verifiedBox.setOnCheckedChangeListener((b, c) -> invalidateOptionsMenu());
                s.addView(verifiedBox);
            }
        } else {
            LinearLayout s = section("Analyse");
            text(s, "⚠️ " + (error != null ? error : "Équipement non reconnu"), 14, true, R.color.vista_coral);
            text(s, requireExisting ? "Choisis le produit dans la liste ci-dessous."
                    : "Tape le nom de l'équipement ou choisis un produit existant.", 12, false, R.color.vista_navy_muted);
        }

        if (!requireExisting) {
            LinearLayout n = section("Nom enregistré");
            nameField = input(n, "ex: Souris Logitech MX Master 3S", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            nameField.setText(detected != null ? detected : "");
            nameField.addTextChangedListener(watcher(() -> {
                if (!settingName && selected != null && !selected.getName().equals(String.valueOf(nameField.getText()))) {
                    selected = null;
                    renderList();
                }
                invalidateOptionsMenu();
            }));

            LinearLayout sn = section("Numéro de série");
            serialField = input(sn, "Numéro de série (S/N)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            serialField.setText(serial != null ? serial : "");
            text(sn, serial != null ? "🧠 Lu par Claude sur la photo — corrige-le si besoin. Il sera envoyé à Jira Assets."
                    : "Optionnel : il sera aussi recherché sur l'étiquette du carton.", 11, false, R.color.vista_navy_muted);
        }

        LinearLayout pl = section(requireExisting ? "Produit à sortir" : "Ou rattacher à un produit existant");
        searchField = input(pl, "Rechercher dans le stock…", InputType.TYPE_CLASS_TEXT);
        searchField.addTextChangedListener(watcher(this::renderList));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        pl.addView(listBox);

        new MainController(this).getStock(products -> {
            all = products;
            if (detected != null && !detected.isEmpty()) {
                candidates = SharedBridge.candidates(detected, all, requireExisting);
                Product best = SharedBridge.bestMatch(detected, all, requireExisting);
                if (best != null) select(best);
            }
            renderList();
        });
    }

    private TextInputEditText input(LinearLayout parent, String hint, int type) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint(hint);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setInputType(type);
        til.addView(et);
        parent.addView(til);
        return et;
    }

    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { r.run(); }
        };
    }

    private void select(Product p) {
        selected = p;
        if (nameField != null) {
            settingName = true;
            nameField.setText(p.getName());
            settingName = false;
        }
        invalidateOptionsMenu();
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        String q = searchField.getText() == null ? "" : searchField.getText().toString().trim();
        Map<Integer, Product> ordered = new LinkedHashMap<>();
        if (q.isEmpty()) {
            for (Product p : candidates) ordered.put(p.getId(), p);
            for (Product p : all) ordered.put(p.getId(), p);
        } else {
            for (Product p : SharedBridge.candidates(q, all, false)) ordered.put(p.getId(), p);
            for (Product p : all) if (norm(p.getName()).contains(norm(q))) ordered.put(p.getId(), p);
        }
        int shown = 0;
        Integer suggestedId = candidates.isEmpty() ? null : candidates.get(0).getId();
        for (Product p : ordered.values()) {
            if (requireExisting && p.getQuantity() <= 0) continue;
            if (shown++ >= 30) break;
            RadioButton rb = new RadioButton(this);
            String label = p.getName() + "\nStock : " + p.getQuantity()
                    + (suggestedId != null && suggestedId == p.getId() ? "   ·   ✨ Suggéré" : "");
            rb.setText(label);
            rb.setChecked(selected != null && selected.getId() == p.getId());
            rb.setPadding(dp(4), dp(8), 0, dp(8));
            rb.setOnClickListener(v -> { select(p); renderList(); });
            listBox.addView(rb);
        }
        if (shown == 0) text(listBox, "Aucun produit", 13, false, R.color.vista_navy_muted);
    }

    private boolean canValidate() {
        if (needsCheck && (verifiedBox == null || !verifiedBox.isChecked())) return false;
        if (requireExisting) return selected != null;
        return nameField != null && nameField.getText() != null && !nameField.getText().toString().trim().isEmpty();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Valider").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem m = menu.findItem(1);
        if (m != null) m.setEnabled(canValidate());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            if (!canValidate()) return true;
            Intent data = new Intent();
            String n = selected != null ? selected.getName()
                    : (nameField != null && nameField.getText() != null ? nameField.getText().toString().trim() : "");
            data.putExtra(EXTRA_NAME, n);
            data.putExtra(EXTRA_PRODUCT_ID, selected != null ? selected.getId() : -1);
            if (serialField != null && serialField.getText() != null) {
                String s = serialField.getText().toString().trim();
                if (!s.isEmpty()) {
                    String norm = SharedBridge.normalizeSerial(s);
                    data.putExtra(EXTRA_SERIAL, norm != null ? norm : s.toUpperCase(Locale.ROOT));
                }
            }
            setResult(RESULT_OK, data);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        setResult(RESULT_CANCELED);
        finish();
        return true;
    }

    static String confidenceLabel(int c) {
        if (c < 0) return "Confiance : inconnue";
        if (c >= 85) return "Confiance élevée · " + c + " %";
        if (c >= 60) return "Confiance moyenne · " + c + " %";
        return "Confiance faible · " + c + " %";
    }

    private int confidenceColor(int c) {
        if (c < 0) return getColor(R.color.vista_navy_muted);
        if (c >= 85) return 0xFF2E9E6B;
        if (c >= 60) return getColor(R.color.vista_amber);
        return getColor(R.color.vista_coral);
    }

    @SuppressWarnings("unused")
    private void hide(View v) { v.setVisibility(View.GONE); }
}
