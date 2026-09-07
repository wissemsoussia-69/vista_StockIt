package com.example.stockit;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.controller.TicketAdapter;
import com.example.stockit.model.JiraTicket;
import com.example.stockit.util.JiraReader;
import com.example.stockit.util.SessionManager;
import com.example.stockit.util.VistaSnackbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

/**
 * StockIT PFE — Liste des tickets Jira ouverts, style Vista v2.
 *
 * Améliorations UX :
 *  - Chips filtres (Tous / Highest / En retard / Assignés à moi)
 *  - Swipe droit  = "Assigné à moi" (fond mint)
 *  - Swipe gauche = "Reporter"       (fond ambre)
 *  - Skeleton shimmer pendant le chargement
 *  - Empty state illustré si aucun ticket
 *
 * Sortie (setResult) :
 *   EXTRA_TICKET_ID (String) — clé du ticket sélectionné
 *   EXTRA_TICKET_SUMMARY (String) — résumé pour affichage
 */
public class TicketListActivity extends AppCompatActivity {

    public static final String EXTRA_TICKET_ID = "ticket_id";
    public static final String EXTRA_TICKET_SUMMARY = "ticket_summary";

    private EditText search;
    private RecyclerView recycler;
    private ProgressBar progress;
    private TextView status;
    private TicketAdapter adapter;
    private View skeletonContainer;
    private View emptyStateRoot;
    private ChipGroup chipGroup;
    private ValueAnimator shimmerAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ticket_list);

        search   = findViewById(R.id.tlSearch);
        recycler = findViewById(R.id.tlRecycler);
        progress = findViewById(R.id.tlProgress);
        status   = findViewById(R.id.tlStatus);
        skeletonContainer = findViewById(R.id.tlSkeletonContainer);
        emptyStateRoot    = findViewById(R.id.emptyStateRoot);
        chipGroup         = findViewById(R.id.tlChipGroup);
        MaterialButton cancel = findViewById(R.id.tlBtnCancel);

        adapter = new TicketAdapter(t -> {
            Intent data = new Intent();
            data.putExtra(EXTRA_TICKET_ID, t.key);
            data.putExtra(EXTRA_TICKET_SUMMARY, t.summary);
            setResult(RESULT_OK, data);
            finish();
        });
        // Utilisé pour le chip "Assignés à moi" — basé sur la session utilisateur.
        String currentAssignee = SessionManager.get(this).getUsername();
        adapter.setCurrentUserAssignee(currentAssignee == null ? "" : currentAssignee.trim());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
                updateEmptyState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Chips filtres
        if (chipGroup != null) {
            chipGroup.setOnCheckedStateChangeListener((group, ids) -> {
                if (ids.isEmpty()) return;
                int id = ids.get(0);
                TicketAdapter.FilterMode mode;
                if (id == R.id.tlChipHighest)      mode = TicketAdapter.FilterMode.HIGHEST;
                else if (id == R.id.tlChipOverdue) mode = TicketAdapter.FilterMode.OVERDUE;
                else if (id == R.id.tlChipMine)    mode = TicketAdapter.FilterMode.MINE;
                else                               mode = TicketAdapter.FilterMode.ALL;
                adapter.setFilterMode(mode);
                group.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                updateEmptyState();
            });
        }

        setupSwipeActions();

        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });

        loadTickets();
    }

    /**
     * Swipe droit  = "Assigné à moi" (mint)
     * Swipe gauche = "Reporter"      (amber)
     * Fond coloré + icône qui apparaissent progressivement pendant le swipe.
     */
    private void setupSwipeActions() {
        final int mint  = ContextCompat.getColor(this, R.color.vista_mint);
        final int amber = ContextCompat.getColor(this, R.color.vista_amber);
        final Drawable icAssign = ContextCompat.getDrawable(this, R.drawable.ic_swipe_assign);
        final Drawable icDefer  = ContextCompat.getDrawable(this, R.drawable.ic_swipe_defer);

        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getBindingAdapterPosition();
                JiraTicket t = adapter.removeAt(pos);
                if (t == null) return;
                if (dir == ItemTouchHelper.RIGHT) {
                    VistaSnackbar.show(recycler, "Assigné à moi : " + t.key,
                            VistaSnackbar.Level.SUCCESS);
                } else {
                    VistaSnackbar.show(recycler, "Reporté : " + t.key,
                            VistaSnackbar.Level.INFO);
                }
                updateEmptyState();
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                View v = vh.itemView;
                Rect bounds = new Rect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());

                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(dX > 0 ? mint : amber);
                float radius = 18f * getResources().getDisplayMetrics().density;
                c.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        radius, radius, p);

                Drawable ic = dX > 0 ? icAssign : icDefer;
                if (ic != null) {
                    int size = (int) (24 * getResources().getDisplayMetrics().density);
                    int cy = bounds.centerY() - size / 2;
                    int cx;
                    if (dX > 0) {
                        cx = bounds.left + (int) (24 * getResources().getDisplayMetrics().density);
                    } else {
                        cx = bounds.right - size - (int) (24 * getResources().getDisplayMetrics().density);
                    }
                    ic.setBounds(cx, cy, cx + size, cy + size);
                    ic.setTint(Color.WHITE);
                    ic.draw(c);
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(cb).attachToRecyclerView(recycler);
    }

    private void loadTickets() {
        showSkeleton(true);
        setBusy(true, "⏳ Chargement des tickets Jira…");
        JiraReader.loadOpenTickets(50, (tickets, error) -> runOnUiThread(() -> {
            setBusy(false, null);
            showSkeleton(false);
            if (error != null) {
                status.setText("❌ " + error);
                showEmpty("Impossible de charger", error);
                return;
            }
            if (tickets == null || tickets.isEmpty()) {
                status.setText("Aucun ticket ouvert.");
                showEmpty("Aucun ticket ouvert", "Rien à traiter — 🎉 profitez d'une pause.");
                return;
            }
            status.setText(tickets.size() + " ticket(s) ouvert(s)");
            adapter.setData(tickets);
            updateEmptyState();
        }));
    }

    private void updateEmptyState() {
        if (adapter.visibleCount() == 0 && !isSkeletonVisible()) {
            showEmpty("Aucun résultat", "Essayez un autre filtre ou une autre recherche.");
        } else {
            hideEmpty();
        }
    }

    private void showEmpty(String title, String subtitle) {
        if (emptyStateRoot == null) return;
        emptyStateRoot.setVisibility(View.VISIBLE);
        TextView t = emptyStateRoot.findViewById(R.id.emptyTitle);
        TextView s = emptyStateRoot.findViewById(R.id.emptySubtitle);
        if (t != null) t.setText(title);
        if (s != null) s.setText(subtitle);
    }

    private void hideEmpty() {
        if (emptyStateRoot != null) emptyStateRoot.setVisibility(View.GONE);
    }

    private boolean isSkeletonVisible() {
        return skeletonContainer != null && skeletonContainer.getVisibility() == View.VISIBLE;
    }

    /** Affiche/masque les skeletons + fait pulser leur opacité pour un effet shimmer. */
    private void showSkeleton(boolean show) {
        if (skeletonContainer == null) return;
        if (show) {
            skeletonContainer.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
            hideEmpty();
            shimmerAnimator = ObjectAnimator.ofFloat(skeletonContainer, "alpha", 0.4f, 1.0f);
            shimmerAnimator.setDuration(800);
            shimmerAnimator.setRepeatMode(ValueAnimator.REVERSE);
            shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
            shimmerAnimator.start();
        } else {
            if (shimmerAnimator != null) { shimmerAnimator.cancel(); shimmerAnimator = null; }
            skeletonContainer.setAlpha(1.0f);
            skeletonContainer.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
        }
    }

    private void setBusy(boolean busy, String label) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (label != null) status.setText(label);
    }

    @Override
    protected void onDestroy() {
        if (shimmerAnimator != null) shimmerAnimator.cancel();
        super.onDestroy();
    }
}
