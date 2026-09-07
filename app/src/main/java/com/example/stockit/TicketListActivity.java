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
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
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
import com.example.stockit.util.VistaSnackbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

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
        String currentUsername = com.example.stockit.util.SessionManager.get(this).getUsername();
        if (currentUsername != null && !currentUsername.isEmpty()) {
            adapter.setCurrentUserAssignee(currentUsername);
        }
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        attachTapSelectionFallback();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
                updateEmptyState();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

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
                    VistaSnackbar.show(recycler, "Assigned to me: " + t.key,
                            VistaSnackbar.Level.SUCCESS);
                } else {
                    VistaSnackbar.show(recycler, "Deferred: " + t.key,
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

    private void attachTapSelectionFallback() {
        final GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(@NonNull MotionEvent e) {
                        return true;
                    }
                });

        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (!detector.onTouchEvent(e)) return false;
                View child = rv.findChildViewUnder(e.getX(), e.getY());
                if (child == null) return false;
                int pos = rv.getChildAdapterPosition(child);
                JiraTicket t = adapter.getVisibleAt(pos);
                if (t == null) return false;

                Intent data = new Intent();
                data.putExtra(EXTRA_TICKET_ID, t.key);
                data.putExtra(EXTRA_TICKET_SUMMARY, t.summary);
                setResult(RESULT_OK, data);
                finish();
                return true;
            }
        });
    }

    private void loadTickets() {
        showSkeleton(true);
        setBusy(true, "Loading Jira tickets...");
        JiraReader.loadOpenTickets(50, (tickets, error) -> runOnUiThread(() -> {
            setBusy(false, null);
            showSkeleton(false);
            if (error != null) {
                status.setText("Error: " + error);
                showEmpty("Unable to load", error);
                return;
            }
            if (tickets == null || tickets.isEmpty()) {
                status.setText(R.string.txt_no_open_ticket);
                showEmpty("No open tickets", "Nothing to process - enjoy a short break.");
                return;
            }
            status.setText(tickets.size() + " open ticket(s) - tap a ticket to assign");
            adapter.setData(tickets);
            updateEmptyState();
        }));
    }

    private void updateEmptyState() {
        if (adapter.visibleCount() == 0 && !isSkeletonVisible()) {
            showEmpty("No results", "Try another filter or another search.");
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
