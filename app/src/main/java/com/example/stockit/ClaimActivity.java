package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.controller.ClaimAdapter;
import com.example.stockit.controller.MainController;
import com.example.stockit.model.Claim;
import com.example.stockit.model.User;
import java.util.ArrayList;

public class ClaimActivity extends AppCompatActivity {
    private ClaimAdapter adapter;
    private MainController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_claim);

        controller = new MainController(this);
        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.claimToolbar);
        if (tb != null) {
            tb.setTitle(L("Réclamations"));
            setSupportActionBar(tb);
            if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        if (!controller.hasClaimsBackend()) {
            Toast.makeText(this, L("Mode local : backend réclamations non configuré (STOCKIT_API_BASE_URL). Les réclamations restent sur ce téléphone."), Toast.LENGTH_LONG).show();
        }

        RecyclerView rv = findViewById(R.id.claimRecyclerView);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ClaimAdapter(new ArrayList<>(), claim -> {
            // Ouvrir la discussion pour ce ticket spécifique
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("CLAIM_ID", claim.getId());
            intent.putExtra("CLAIM_SUBJECT", claim.getSubject());
            startActivity(intent);
        });
        rv.setAdapter(adapter);

        findViewById(R.id.fabAddClaim).setOnClickListener(v -> showAddClaimDialog());

        loadClaims();
    }

    private void loadClaims() {
        controller.getClaims(claims -> adapter.setClaims(claims));
    }

    private void showAddClaimDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_add_claim, null);
        EditText editSubject = view.findViewById(R.id.editClaimSubject);
        EditText editDesc = view.findViewById(R.id.editClaimDesc);
        Spinner spinnerPriority = view.findViewById(R.id.spinnerClaimPriority);

        new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(L("Envoyer"), (dialog, which) -> {
                    String sub = editSubject.getText().toString();
                    String desc = editDesc.getText().toString();
                    String prio = spinnerPriority.getSelectedItem().toString();

                    if (!sub.isEmpty()) {
                        controller.addClaim(sub, desc, prio, () -> {
                            Toast.makeText(this, L("Réclamation enregistrée"), Toast.LENGTH_SHORT).show();
                            loadClaims();
                        });
                    } else {
                        Toast.makeText(this, L("Le sujet est obligatoire"), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(L("Annuler"), null)
                .show();
    }

    private static String L(String key, Object... args) { return com.example.stockit.util.AppLanguage.L(key, args); }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
