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
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import java.util.ArrayList;

public class ClaimActivity extends AppCompatActivity {
    private ClaimAdapter adapter;
    private MainController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_claim);

        controller = new MainController(this);

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
                .setPositiveButton("Envoyer", (dialog, which) -> {
                    String sub = editSubject.getText().toString();
                    String desc = editDesc.getText().toString();
                    String prio = spinnerPriority.getSelectedItem().toString();

                    if (!sub.isEmpty()) {
                        controller.addClaim(sub, desc, prio, this::loadClaims);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }
}
