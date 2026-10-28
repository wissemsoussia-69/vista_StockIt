package com.example.stockit;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.controller.AuditLogAdapter;
import com.example.stockit.controller.CategoryAdapter;
import com.example.stockit.controller.MainController;
import com.example.stockit.controller.ProductAdapter;
import com.example.stockit.controller.PurchaseOrderAdapter;
import com.example.stockit.controller.ShippingOrderAdapter;
import com.example.stockit.controller.StockMovementAdapter;
import com.example.stockit.controller.SupplierAdapter;
import com.example.stockit.controller.UserAdapter;
import com.example.stockit.model.Product;
import com.google.android.material.navigation.NavigationView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    private MainController controller;
    private DrawerLayout drawerLayout;
    
    private View layoutStock, layoutDashboard, layoutPurchase, layoutReports, layoutManageUser, 
                 layoutArticle, layoutCategory, layoutAudit, layoutSupplier, layoutShipping, 
                 layoutAI, layoutLeaderboard, layoutSettings;

    private ActivityResultLauncher<Uri> takePictureLauncher;
    private ActivityResultLauncher<Intent> batchScanLauncher;
    private Uri photoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TM-33 : captures d'écran / aperçu multitâche désactivés sur cet écran.
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE);
        // Garde : aucun accès sans utilisateur connecté (processus relancé par le système, etc.).
        if (MainController.getCurrentUser() == null) {
            com.example.stockit.util.SessionManager sm = com.example.stockit.util.SessionManager.get(this);
            if (sm != null && sm.isLoggedIn() && sm.getUsername() != null) {
                // Restaure l'utilisateur depuis la session de ce lancement, puis relance l'écran.
                new MainController(this).restoreSession(sm.getUsername(), sm.getRole(), this::recreate);
                setContentView(new android.widget.ProgressBar(this));
                return;
            }
            Intent i = new Intent(this, SplashActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        controller = new MainController(this);
        drawerLayout = findViewById(R.id.drawer_layout);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.open_drawer, R.string.close_drawer);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        initLayouts();
        setupDashboardButtons();
        setupArticleForm();
        setupVistaFab();
        setupAlertPulse();
        setupBottomNav();

        takePictureLauncher = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            if (success) processImageFromUri(photoUri);
        });

        batchScanLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                ArrayList<String> codes = result.getData().getStringArrayListExtra("codes");
                if (codes != null && !codes.isEmpty()) {
                    controller.addOrIncrementByCodes(codes, (created, updated) -> {
                        Toast.makeText(this, L("Scan en rafale : %@ créé(s), %@ incrémenté(s)", created, updated),
                                Toast.LENGTH_LONG).show();
                        refreshList();
                        updateDashboardData();
                    });
                }
            }
        });

        setupSearch();
        findViewById(R.id.refreshButton).setOnClickListener(v -> refreshList());
        setupAssetsSync();
        View scanInv = findViewById(R.id.btnScanInventory);
        if (scanInv != null) scanInv.setOnClickListener(v -> startBatchScan());
        View history = findViewById(R.id.btnMovementHistory);
        if (history != null) history.setOnClickListener(v -> showAllMovements());
        setupCrudFabs();
        setupCategoryForm();
        setupReportsButtons();
        applyNavHeader();
        applyRoleVisibility(navigationView);
        requestNotificationPermission();

        showDashboard();

        // Raccourci launcher "Ajouter un produit".
        if (com.example.stockit.SplashActivity.ACTION_ADD_PRODUCT.equals(getIntent().getAction())) {
            showLayout(layoutArticle);
            refreshArticles();
        }

        // Seed initial data if empty (aucune donnée de démonstration si Jira Assets est configuré, comme iOS)
        controller.getStock(products -> {
            if (products.isEmpty()) {
                controller.seedStock(this::updateDashboardData);
            }
        });

        // Connexion : renvoi des webhooks en attente + synchronisation silencieuse Jira Assets (iOS didLogin).
        if (savedInstanceState == null) {
            com.example.stockit.util.WebhookOutbox.flush(this, null);
            com.example.stockit.util.AssetsSync.sync(null);
        }
    }

    // ================================================================
    //  Jira Assets → inventaire (iOS StockListView / AppState.syncAssets)
    // ================================================================
    private final Runnable assetsListener = this::onAssetsSyncStateChanged;

    private void setupAssetsSync() {
        View card = findViewById(R.id.assetsSyncCard);
        if (card != null) card.setVisibility(com.example.stockit.util.AssetsSync.isConfigured() ? View.VISIBLE : View.GONE);
        View btn = findViewById(R.id.btnAssetsSync);
        if (btn != null) btn.setOnClickListener(v -> syncAssets(false));
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout srl = findViewById(R.id.stockSwipeRefresh);
        if (srl != null) {
            srl.setColorSchemeResources(R.color.vista_blue);
            srl.setOnRefreshListener(() -> {
                if (com.example.stockit.util.AssetsSync.isConfigured()) syncAssets(false);
                else { refreshList(); srl.setRefreshing(false); }
            });
        }
        com.example.stockit.util.AssetsSync.addListener(assetsListener);
        onAssetsSyncStateChanged();
    }

    /** Jira Assets → inventaire (source de vérité). silent : pas de message si tout va bien. */
    private void syncAssets(boolean silent) {
        com.example.stockit.util.AssetsSync.sync(r -> {
            if (r.success) {
                if (!silent) com.example.stockit.util.VistaSnackbar.show(findViewById(R.id.content_frame),
                        L("✅ Inventaire synchronisé : %@ modèles, %@ unités", r.products, r.units),
                        com.example.stockit.util.VistaSnackbar.Level.SUCCESS);
            } else if (!silent && !"sync_in_progress".equals(r.error)) {
                com.example.stockit.util.VistaSnackbar.show(findViewById(R.id.content_frame),
                        L("Synchronisation impossible") + (r.error != null ? " — " + r.error : ""),
                        com.example.stockit.util.VistaSnackbar.Level.URGENT);
            }
        });
    }

    /** Bandeau (dernière synchro / en cours) + rechargement des écrans après une synchro. */
    private void onAssetsSyncStateChanged() {
        boolean syncing = com.example.stockit.util.AssetsSync.isSyncing();
        View prog = findViewById(R.id.assetsSyncProgress);
        View btn = findViewById(R.id.btnAssetsSync);
        if (prog != null) prog.setVisibility(syncing ? View.VISIBLE : View.GONE);
        if (btn != null) btn.setVisibility(syncing ? View.GONE : View.VISIBLE);
        android.widget.TextView label = findViewById(R.id.assetsSyncLabel);
        long last = com.example.stockit.util.AssetsSync.getLastSyncMs();
        if (label != null) {
            if (last == 0) label.setText(L("Tirez vers le bas pour synchroniser"));
            else label.setText(L("Dernière synchronisation : %@",
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT,
                            com.example.stockit.util.AppLanguage.locale()).format(new java.util.Date(last))));
        }
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout srl = findViewById(R.id.stockSwipeRefresh);
        if (srl != null && !syncing) srl.setRefreshing(false);
        if (!syncing) {
            if (layoutStock != null && layoutStock.getVisibility() == View.VISIBLE) reloadStockList();
            if (layoutDashboard != null && layoutDashboard.getVisibility() == View.VISIBLE) updateDashboardData();
            StockWidget.refreshAll(this);
        }
    }

    @Override
    protected void onDestroy() {
        com.example.stockit.util.AssetsSync.removeListener(assetsListener);
        super.onDestroy();
    }

    private static String L(String key, Object... args) {
        return com.example.stockit.util.AppLanguage.L(key, args);
    }

    private void initLayouts() {
        layoutDashboard = findViewById(R.id.layout_dashboard);
        layoutStock = findViewById(R.id.layout_stock);
        layoutPurchase = findViewById(R.id.layout_purchase);
        layoutReports = findViewById(R.id.layout_reports);
        layoutManageUser = findViewById(R.id.layout_manage_user);
        layoutArticle = findViewById(R.id.layout_article);
        layoutCategory = findViewById(R.id.layout_category);
        layoutAudit = findViewById(R.id.layout_audit);
        layoutSupplier = findViewById(R.id.layout_supplier);
        layoutShipping = findViewById(R.id.layout_shipping);
        layoutAI = findViewById(R.id.layout_ai);
        layoutLeaderboard = findViewById(R.id.layout_leaderboard);
        layoutSettings = findViewById(R.id.layout_settings);
    }

    private void showLayout(View layout) {
        // Diagnostic Log
        android.util.Log.d("MainActivity", "Swapping to layout: " + (layout != null ? layout.getId() : "NULL"));

        View[] layouts = {layoutDashboard, layoutStock, layoutPurchase, layoutReports, 
                         layoutManageUser, layoutArticle, layoutCategory, layoutAudit, 
                         layoutSupplier, layoutShipping, layoutAI, layoutLeaderboard, layoutSettings};
        
        for (View l : layouts) {
            if (l != null) {
                l.setVisibility(View.GONE);
                l.setEnabled(false);
                l.setAlpha(0f); // Make it fully transparent when hidden
            }
        }
        
        if (layout != null) {
            layout.setVisibility(View.VISIBLE);
            layout.setEnabled(true);
            layout.setAlpha(1f);
            layout.bringToFront();
            layout.invalidate(); // Force redraw
        }
    }

    private void showDashboard() {
        showLayout(layoutDashboard);
        updateDashboardData();
    }

    private void setupDashboardButtons() {
        // Les actions caméra (Scanner asset, Réception colis, Sortie équipement)
        // sont désormais centralisées dans le FAB Vista (voir setupVistaFab).
        // Le dashboard ne garde que 3 tuiles navigation : Stock / Article / IA.
        if (findViewById(R.id.btnQuickAdd) != null)
            findViewById(R.id.btnQuickAdd).setOnClickListener(v -> showScanMenu());
        if (findViewById(R.id.btnNewMovement) != null)
            findViewById(R.id.btnNewMovement).setOnClickListener(v -> { showLayout(layoutStock); refreshList(); });
        if (findViewById(R.id.btnVoiceControl) != null)
            findViewById(R.id.btnVoiceControl).setOnClickListener(v -> startActivity(new Intent(this, AIChatActivity.class)));
        if (findViewById(R.id.btnViewAllMovements) != null)
            findViewById(R.id.btnViewAllMovements).setOnClickListener(v -> { showLayout(layoutStock); refreshList(); });
    }

    // ================================================================
    //  Vista FAB — bouton central bleu + menu satellite morphé
    // ================================================================
    private boolean fabMenuOpen = false;

    private void setupVistaFab() {
        final com.google.android.material.floatingactionbutton.FloatingActionButton fab =
                findViewById(R.id.fabVistaCentral);
        final View miniMenu = findViewById(R.id.fabMiniMenu);
        if (fab == null || miniMenu == null) return;

        // Scanner asset (mini FAB)
        View scanMini = findViewById(R.id.fabActionScan);
        if (scanMini != null) scanMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startActivity(new Intent(this, ScanAssetActivity.class));
        });

        // Réception colis (mini FAB)
        View receiveMini = findViewById(R.id.fabActionReceive);
        if (receiveMini != null) receiveMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startActivity(new Intent(this, ReceivePackageActivity.class));
        });

        // Sortie équipement (mini FAB)
        View shipMini = findViewById(R.id.fabActionShipOut);
        if (shipMini != null) shipMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startActivity(new Intent(this, ScanOutActivity.class));
        });

        // Scan codes-barres en rafale (mini FAB) — 4e action caméra, identique iOS.
        View batchMini = findViewById(R.id.fabActionBatch);
        if (batchMini != null) batchMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startBatchScan();
        });

        fab.setOnClickListener(v -> toggleFabMenu(fab, miniMenu, !fabMenuOpen));
    }

    /**
     * Transition fluide : le bouton bleu Vista "s'étire" (rotation 45° +
     * léger scale) et fait apparaître les mini FAB en pop-in animé.
     */
    private void toggleFabMenu(View fab, View miniMenu, boolean open) {
        fabMenuOpen = open;

        // Haptic feedback discret pour marquer l'interaction (compat >= API 26).
        fab.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);

        // Rotation + subtle scale sur le FAB central pour marquer l'état
        fab.animate()
                .rotation(open ? 45f : 0f)
                .scaleX(open ? 1.05f : 1.0f)
                .scaleY(open ? 1.05f : 1.0f)
                .setDuration(220)
                .start();

        if (open) {
            miniMenu.setVisibility(View.VISIBLE);
            miniMenu.startAnimation(android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.fab_mini_pop_in));
        } else {
            android.view.animation.Animation out = android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.fab_mini_pop_out);
            out.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                @Override public void onAnimationStart(android.view.animation.Animation a) {}
                @Override public void onAnimationRepeat(android.view.animation.Animation a) {}
                @Override public void onAnimationEnd(android.view.animation.Animation a) {
                    miniMenu.setVisibility(View.GONE);
                }
            });
            miniMenu.startAnimation(out);
        }
    }

    /** Pastille corail "vivante" du bandeau d'alertes (pulse continu). */
    private void setupAlertPulse() {
        View dot = findViewById(R.id.dashAlertPulse);
        if (dot != null) {
            dot.startAnimation(android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.pulse_urgency));
        }
    }

    /**
     * Bottom Navigation Vista : 4 destinations principales.
     * Tickets ouvre TicketListActivity (activité dédiée).
     * Le FAB central Scanner reste flottant par-dessus.
     */
    private void setupBottomNav() {
        com.google.android.material.bottomnavigation.BottomNavigationView bnav =
                findViewById(R.id.bottomNav);
        if (bnav == null) return;
        bnav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            bnav.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (id == R.id.bnav_dashboard) { showDashboard(); return true; }
            if (id == R.id.bnav_stock)     { showLayout(layoutStock); refreshList(); return true; }
            if (id == R.id.bnav_tickets)   { startActivity(new Intent(this, TicketListActivity.class)); return true; }
            if (id == R.id.bnav_profile)   { startActivity(new Intent(this, ProfileActivity.class)); return true; }
            return false;
        });
        bnav.setSelectedItemId(R.id.bnav_dashboard);
    }

    /** Actions caméra (iOS : onglet Scanner / tuile Scan du dashboard). */
    private void showScanMenu() {
        String[] items = {
                L("🧠 Scanner un équipement (IA)"),
                L("📦 Réception colis (facture)"),
                L("📤 Sortie équipement"),
                L("🔢 Scan codes-barres en rafale"),
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L("Actions caméra"))
                .setItems(items, (d, i) -> {
                    if (i == 0) startActivity(new Intent(this, ScanAssetActivity.class));
                    else if (i == 1) startActivity(new Intent(this, ReceivePackageActivity.class));
                    else if (i == 2) startActivity(new Intent(this, ScanOutActivity.class));
                    else startBatchScan();
                })
                .setNegativeButton(L("Annuler"), null)
                .show();
    }

    private void startBatchScan() {
        batchScanLauncher.launch(new Intent(this, BatchScanActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null && layoutDashboard != null && layoutDashboard.getVisibility() == View.VISIBLE) {
            updateDashboardData();
        }
        if (controller != null) StockWidget.refreshAll(this);
    }

    private void startScanIA() {
        try {
            File photoFile = File.createTempFile("SCAN_", ".jpg", getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES));
            photoUri = androidx.core.content.FileProvider.getUriForFile(this, "com.example.stockit.fileprovider", photoFile);
            takePictureLauncher.launch(photoUri);
        } catch (Exception e) { Toast.makeText(this, L("Erreur caméra"), Toast.LENGTH_SHORT).show(); }
    }

    private void processImageFromUri(Uri uri) {
        final androidx.appcompat.app.AlertDialog progress = new androidx.appcompat.app.AlertDialog.Builder(this).setMessage(L("Analyse Vision Gemini...")).setCancelable(false).show();
        controller.detectObjectsGemini(uri, (labels, visionText) -> {
            progress.dismiss();
            String bestLabel = labels.isEmpty() ? "Objet IT" : labels.get(0);
            traiterResultatIA(bestLabel, visionText, uri);
        });
    }

    private void traiterResultatIA(String label, String visionText, Uri uri) {
        // --- OPTIMISATION D'IMAGE (Détourage + WebP) ---
        com.example.stockit.util.ImageOptimizerUtil.optimizeImage(this, uri, (optimizedFile, oldSize, newSize) -> {
            double saved = (1.0 - (double)newSize / (oldSize > 0 ? oldSize : 1000000)) * 100;
            String optiText = L("\n\n⚡ Optimisation : %@%% d'espace gagné (Format WebP)", String.format(Locale.getDefault(), "%.0f", saved > 0 ? saved : 85));

            String displayMsg = L("Matériel détecté par Gemini : %@", label) + optiText;

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(L("Analyse Vision Gemini 1.5"))
                    .setMessage(displayMsg)
                    .setPositiveButton(L("Ajouter"), (d, w) -> {
                        controller.addProduct(label, "Informatique", null, "Scan Gemini Vision", "ASSET-" + System.currentTimeMillis(), 1, 0.0, "", "", "Scan IA", () -> {
                            refreshList();
                            updateDashboardData();
                            Toast.makeText(this, L("%@ ajouté !", label), Toast.LENGTH_SHORT).show();

                        });
                    })
                    .setNegativeButton(L("Réessayer"), (d, w) -> startScanIA())
                    .show();
        });
    }

    private void updateDashboardData() {
        // Salutation + avatar Vista (basé sur le nom présent dans le nav header)
        applyHeroGreeting();
        renderHeroChart();

        controller.getReportData((total, low, value, out, counts) -> {
            android.widget.TextView txtTotal = findViewById(R.id.dashTotalStock);
            android.widget.TextView txtValue = findViewById(R.id.dashTotalValue);
            android.widget.TextView txtAlerts = findViewById(R.id.dashAlertText);
            android.widget.TextView txtHeroSub = findViewById(R.id.dashHeroSubtitle);

            // Compteurs animés (0 → valeur) pour un effet premium
            if (txtTotal != null) animateInt(txtTotal, total, null);
            if (txtValue != null) animateDouble(txtValue, value, " €");
            if (txtAlerts != null) txtAlerts.setText(L("%@ en rupture, %@ bas", out, low));
            if (txtHeroSub != null) txtHeroSub.setText(
                    L("%d articles · %d en rupture", total, out));
        });

        // --- QUÊTES (LUDIFICATION) ---
        controller.getQuests(quests -> {
            if (!quests.isEmpty()) {
                com.example.stockit.model.Quest q = quests.get(0);
                android.widget.TextView title = findViewById(R.id.dashQuestTitle);
                android.widget.TextView desc = findViewById(R.id.dashQuestDesc);
                ProgressBar prog = findViewById(R.id.dashQuestProgress);
                if (title != null) title.setText(com.example.stockit.util.AppLanguage.LV(q.getTitle()));
                if (desc != null) desc.setText(com.example.stockit.util.AppLanguage.LV(q.getDescription()) + " (" + q.getCurrentCount() + "/" + q.getGoalCount() + ")");
                if (prog != null) {
                    prog.setMax(q.getGoalCount());
                    prog.setProgress(q.getCurrentCount());
                }
            }
        });

        controller.getStockMovements(movements -> {
            RecyclerView rv = findViewById(R.id.dashRecentMovements);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                Collections.reverse(movements);
                List<com.example.stockit.model.StockMovement> limited = movements.size() > 3 ? movements.subList(0, 3) : movements;
                rv.setAdapter(new StockMovementAdapter(limited));
            }
        });
    }

    private void refreshList() { reloadStockList(); }

    /** Recharge la liste (avec la recherche en cours) + état vide (iOS EmptyStateView). */
    private void reloadStockList() {
        SearchView sv = findViewById(R.id.searchView);
        String q = sv != null && sv.getQuery() != null ? sv.getQuery().toString().trim() : "";
        MainController.StockCallback cb = this::showStock;
        if (q.isEmpty()) controller.getStock(cb); else controller.searchProducts(q, cb);
    }

    private void showStock(List<Product> products) {
        RecyclerView rv = findViewById(R.id.recyclerView);
        if (rv != null) {
            if (rv.getLayoutManager() == null) rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new ProductAdapter(products, productActionListener));
        }
        View empty = findViewById(R.id.stockEmptyState);
        if (empty != null) empty.setVisibility(products.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final ProductAdapter.OnProductActionListener productActionListener = new ProductAdapter.OnProductActionListener() {
        @Override public void onUpdateQuantity(Product p, int d) {
            if (MainController.getCurrentUser() == null) { Toast.makeText(MainActivity.this, L("Connexion requise"), Toast.LENGTH_SHORT).show(); return; }
            controller.updateQuantity(p, d, () -> { refreshList(); updateDashboardData(); StockWidget.refreshAll(MainActivity.this); });
        }
        @Override public void onShowDetails(Product p) { showProductDetails(p); }
        @Override public void onShowMovement(Product p) { showProductTimeline(p); }
    };

    private void showProductDetails(Product p) {
        View view = getLayoutInflater().inflate(R.layout.dialog_product_details, null);
        ((android.widget.TextView)view.findViewById(R.id.detailName)).setText(p.getName());
        StringBuilder info = new StringBuilder();
        info.append(L("Stock actuel : %@  (seuil %@)", p.getQuantity(), p.getMinThreshold()));
        if (p.getQuantity() == 0) info.append(L("  — 🔴 RUPTURE"));
        else if (p.getQuantity() <= p.getMinThreshold()) info.append(L("  — 🟠 STOCK BAS"));
        appendField(info, "Catégorie", p.getCategory());
        appendField(info, "Description", p.getDescription());
        appendField(info, "Asset tag", p.getAssetTag());
        appendField(info, "Marque", p.getBrand());
        appendField(info, "Réf. article", p.getArticleNumber());
        if (p.getUnitPrice() > 0) info.append(L("\nPrix unitaire : %@ € (valeur %@ €)", String.format(Locale.FRANCE, "%.2f", p.getUnitPrice()), String.format(Locale.FRANCE, "%.2f", p.getUnitPrice() * p.getQuantity())));
        appendField(info, "PO facture", p.getPoNumber());
        appendField(info, "Description PO", p.getPoDescription());
        appendField(info, "PO étiquette", p.getPackagePoNumber());
        appendField(info, "Fournisseur", p.getReceivedFrom());
        appendField(info, "Fabrication", p.getManufacturingDate());
        appendField(info, "Expiration", p.getExpirationDate());
        ((android.widget.TextView)view.findViewById(R.id.detailStock)).setText(info.toString());

        // Kit anti-oubli (même règle que iOS : écran / monitor / moniteur)
        android.widget.TextView txtKit = view.findViewById(R.id.detailKit);
        String n = p.getName() == null ? "" : p.getName().toLowerCase();
        if (n.contains("écran") || n.contains("ecran") || n.contains("monitor") || n.contains("moniteur")) {
            txtKit.setText(L("⚠️ KIT : Câble HDMI + Alimentation"));
            txtKit.setVisibility(View.VISIBLE);
        }

        Button draftBtn = view.findViewById(R.id.btnDetailZycus);
        draftBtn.setText(L("🛒 BROUILLON DE COMMANDE IA"));
        // Brouillon de commande : réservé ADMIN / MANAGER (identique iOS).
        draftBtn.setVisibility(controller.canEditStock() ? View.VISIBLE : View.GONE);
        ((Button) view.findViewById(R.id.btnDetailAlert)).setText(L("🚨 ALERTE N8N / SLACK"));

        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this).setView(view);
        if (controller.canEditStock()) b.setNeutralButton(L("✏️ Modifier"), (d, w) -> showProductForm(p));
        if (controller.isAdmin()) b.setNegativeButton(L("🗑 Supprimer"), (d, w) -> confirmDeleteProduct(p));
        b.setPositiveButton(L("Fermer"), null);
        androidx.appcompat.app.AlertDialog dialog = b.create();
        view.findViewById(R.id.btnDetailChrono).setOnClickListener(v -> { dialog.dismiss(); showProductTimeline(p); });
        view.findViewById(R.id.btnDetailZycus).setOnClickListener(v -> {
            dialog.dismiss();
            openOrderDraft(p);
        });
        view.findViewById(R.id.btnDetailAlert).setOnClickListener(v -> {
            dialog.dismiss();
            controller.triggerN8nAlert(p, () -> Toast.makeText(this, L("Alerte envoyée !"), Toast.LENGTH_LONG).show());
        });
        dialog.show();
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.trim().isEmpty()) sb.append(L("\n%@ : %@", L(label), value));
    }

    private void confirmDeleteProduct(Product p) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L("Supprimer %@ ?", p.getName()))
                .setMessage(L("Cette action est définitive."))
                .setPositiveButton(L("Supprimer"), (d, w) -> controller.deleteProduct(p, () -> {
                    refreshList(); refreshArticles(); updateDashboardData();
                    Toast.makeText(this, L("%@ supprimé", p.getName()), Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton(L("Annuler"), null).show();
    }

    /** Brouillon de commande IA pour un produit (iOS OrderDraftView). */
    private void openOrderDraft(Product p) {
        final androidx.appcompat.app.AlertDialog loading = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(L("🧠 Calcul de la prévision et rédaction du brouillon…")).setCancelable(false).show();
        com.example.stockit.util.SharedBridge.forecastFor(this, p.getId(), list -> {
            if (list.isEmpty()) { loading.dismiss(); Toast.makeText(this, L("Prévision indisponible"), Toast.LENGTH_SHORT).show(); return; }
            com.example.stockit.util.SharedBridge.draftOrder(this, list.get(0), draft -> {
                loading.dismiss();
                ForecastActivity.showDraftDialog(this, controller, draft, null);
            });
        });
    }

    /** Formulaire création / édition produit (iOS ProductFormView). */
    private void showProductForm(@androidx.annotation.Nullable Product existing) {
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText name = field(form, "Nom", existing != null ? existing.getName() : "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText cat = field(form, "Catégorie", existing != null ? existing.getCategory() : "Informatique", android.text.InputType.TYPE_CLASS_TEXT);
        EditText desc = field(form, "Description", existing != null ? existing.getDescription() : "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText tag = field(form, "Asset tag", existing != null ? existing.getAssetTag() : "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText qty = field(form, "Quantité", existing != null ? String.valueOf(existing.getQuantity()) : "1", android.text.InputType.TYPE_CLASS_NUMBER);
        EditText price = field(form, "Prix unitaire (€)", existing != null ? String.valueOf(existing.getUnitPrice()) : "0",
                android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText min = field(form, "Seuil minimum", existing != null ? String.valueOf(existing.getMinThreshold()) : "5", android.text.InputType.TYPE_CLASS_NUMBER);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(form);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(existing == null ? L("Nouveau produit") : L("Modifier %@", existing.getName()))
                .setView(sv)
                .setPositiveButton(L("Enregistrer"), (d, w) -> {
                    String nm = name.getText().toString().trim();
                    if (nm.isEmpty()) { Toast.makeText(this, L("Nom obligatoire"), Toast.LENGTH_SHORT).show(); return; }
                    int q = parseIntOr(qty, 0), m = parseIntOr(min, 5);
                    double pr;
                    try { pr = Double.parseDouble(price.getText().toString().replace(',', '.')); } catch (Exception e) { pr = 0; }
                    if (existing == null) {
                        controller.addProduct(nm, cat.getText().toString().trim(), null, desc.getText().toString().trim(),
                                tag.getText().toString().trim().isEmpty() ? "ASSET-" + System.currentTimeMillis() : tag.getText().toString().trim(),
                                q, pr, "", "", "Ajout manuel", () -> {
                                    Toast.makeText(this, L("%@ ajouté", nm), Toast.LENGTH_SHORT).show();
                                    refreshList(); refreshArticles(); updateDashboardData();
                                });
                    } else {
                        existing.setName(nm);
                        existing.setCategory(cat.getText().toString().trim());
                        existing.setDescription(desc.getText().toString().trim());
                        existing.setAssetTag(tag.getText().toString().trim());
                        existing.setQuantity(q);
                        existing.setUnitPrice(pr);
                        existing.setMinThreshold(m);
                        controller.updateProduct(existing, () -> {
                            Toast.makeText(this, L("%@ mis à jour", nm), Toast.LENGTH_SHORT).show();
                            refreshList(); refreshArticles(); updateDashboardData();
                        });
                    }
                })
                .setNegativeButton(L("Annuler"), null)
                .show();
    }

    private EditText field(android.widget.LinearLayout parent, String hint, String value, int inputType) {
        com.google.android.material.textfield.TextInputLayout til = new com.google.android.material.textfield.TextInputLayout(this);
        til.setHint(L(hint));
        com.google.android.material.textfield.TextInputEditText et = new com.google.android.material.textfield.TextInputEditText(til.getContext());
        et.setInputType(inputType);
        et.setText(value == null ? "" : value);
        til.addView(et);
        parent.addView(til);
        return et;
    }

    private static int parseIntOr(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception ex) { return def; }
    }

    private void showProductTimeline(Product p) {
        controller.getProductMovements(p.getId(), movements -> {
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new com.example.stockit.controller.TimelineAdapter(movements));
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(L("Timeline : %@", p.getName())).setView(rv).setPositiveButton("OK", null).show();
        });
    }

    private void setupArticleForm() {
        Button btn = findViewById(R.id.btnValidateArticle);
        if (btn == null) {
            android.util.Log.e("MainActivity", "btnValidateArticle NOT FOUND in layout!");
            return;
        }
        
        btn.setOnClickListener(v -> {
            EditText inputName = findViewById(R.id.inputArtName);
            EditText inputQty = findViewById(R.id.inputArtQty);
            EditText inputPrice = findViewById(R.id.inputArtPrice);
            EditText inputReason = findViewById(R.id.inputArtReason);
            
            String name = inputName != null ? inputName.getText().toString().trim() : "";
            String qtyS = inputQty != null ? inputQty.getText().toString().trim() : "";
            String priceS = inputPrice != null ? inputPrice.getText().toString().trim() : "0";
            String reason = inputReason != null ? inputReason.getText().toString().trim() : "Ajout manuel";
            
            if (name.isEmpty() || qtyS.isEmpty()) {
                Toast.makeText(this, L("Veuillez remplir le nom et la quantité"), Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int q = Integer.parseInt(qtyS);
                double p = Double.parseDouble(priceS);
                
                controller.addProduct(name, "Informatique", null, "Saisie manuelle", "ASSET-" + System.currentTimeMillis(), q, p, "", "", reason, () -> {
                    Toast.makeText(this, L("Article %@ ajouté avec succès !", name), Toast.LENGTH_SHORT).show();
                    if (inputName != null) inputName.setText("");
                    if (inputQty != null) inputQty.setText("");
                    if (inputPrice != null) inputPrice.setText("");
                    if (inputReason != null) inputReason.setText("");
                    refreshArticles();
                    updateDashboardData();
                });
            } catch (Exception e) {
                Toast.makeText(this, L("Erreur de format (Nombre attendu)"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshArticles() {
        controller.getStock(products -> {
            RecyclerView rv = findViewById(R.id.articleRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new com.example.stockit.controller.ArticleAdapter(products, new com.example.stockit.controller.ArticleAdapter.OnArticleActionListener() {
                    @Override public void onEdit(Product p) { showProductForm(p); }
                    @Override public void onDelete(Product p) { confirmDeleteProduct(p); }
                    @Override public void onDetail(Product p) { showProductDetails(p); }
                }));
            }
        });
    }

    private void setupSearch() {
        SearchView sv = findViewById(R.id.searchView);
        if (sv != null) sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q) { return false; }
            @Override public boolean onQueryTextChange(String q) {
                reloadStockList();
                return true;
            }
        });
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        // Dashboard / Stock / Profil sont dans la BottomNavigationView : plus dans le drawer.
        if (id == R.id.nav_article) { showLayout(layoutArticle); refreshArticles(); }
        else if (id == R.id.nav_analyses) { showLayout(layoutReports); refreshReports(); }
        else if (id == R.id.nav_ai) { showLayout(layoutAI); refreshAI(); }
        else if (id == R.id.nav_fournisseur) { showLayout(layoutSupplier); refreshSuppliers(); }
        else if (id == R.id.nav_commandes) { showLayout(layoutPurchase); refreshPurchases(); }
        else if (id == R.id.nav_shipping) { showLayout(layoutShipping); refreshShipping(); }
        else if (id == R.id.nav_utilisateur) { showLayout(layoutManageUser); refreshUsers(); }
        else if (id == R.id.nav_audit) { showLayout(layoutAudit); refreshAudit(); }
        else if (id == R.id.nav_leaderboard) { showLayout(layoutLeaderboard); refreshLeaderboard(); }
        else if (id == R.id.nav_import_pdf) { generateMonthlyPdfReport(); }
        else if (id == R.id.nav_categories) { showLayout(layoutCategory); refreshCategories(); }
        else if (id == R.id.nav_batch_scan) { startBatchScan(); }
        else if (id == R.id.nav_ai_chat) { startActivity(new Intent(this, AIChatActivity.class)); }
        else if (id == R.id.nav_forecast) { startActivity(new Intent(this, ForecastActivity.class)); }
        else if (id == R.id.nav_ai_quality) { startActivity(new Intent(this, AIQualityActivity.class)); }
        else if (id == R.id.nav_reclamation) { startActivity(new Intent(this, ClaimActivity.class)); }
        else if (id == R.id.nav_configuration) { startActivity(new Intent(this, SettingsActivity.class)); }
        else if (id == R.id.nav_deconnexion) { performLogout(); }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    /**
     * Déconnexion complète : révoque la session Auth0 (Vista SSO), efface les
     * credentials chiffrés locaux et redirige vers l'écran de connexion.
     * Fonctionne aussi si l'utilisateur s'était connecté en mode démo hors ligne
     * (le manager Auth0 gère les deux cas et rappelle onComplete dans tous les cas).
     */
    private void performLogout() {
        MainController.logoutLocal();
        com.example.stockit.util.SessionManager.get(this).clear();
        com.example.stockit.util.Auth0Manager.get(this).signOut(this, () -> {
            Intent i = new Intent(MainActivity.this, SplashActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
    }

    private void refreshReports() {
        controller.getReportData((total, low, value, out, counts) -> {
            android.widget.TextView tv = findViewById(R.id.reportTotalValue);
            if (tv != null) tv.setText(String.format("%.2f €", value));
            
            android.widget.TextView tvOut = findViewById(R.id.reportOutOfStock);
            if (tvOut != null) tvOut.setText(L("%@ articles", out));
            
            android.widget.TextView tvItems = findViewById(R.id.reportTotalItems);
            if (tvItems != null) tvItems.setText(L("Articles en stock: %@", total));
            android.widget.TextView tvLow = findViewById(R.id.reportLowStockCount);
            if (tvLow != null) tvLow.setText(L("Alertes niveau faible: %@", low));
            android.widget.TextView tvCat = findViewById(R.id.reportCategoryDetails);
            if (tvCat != null && counts != null) {
                StringBuilder sb = new StringBuilder();
                for (com.example.stockit.model.ProductDao.CategoryCount c : counts) {
                    sb.append("• ").append(c.category == null ? L("Sans catégorie") : com.example.stockit.util.AppLanguage.LV(c.category)).append(" : ").append(c.total).append("\n");
                }
                tvCat.setText(sb.length() == 0 ? L("Aucune donnée") : sb.toString().trim());
            }

            // --- PIE CHART (Distribution) — vue maison, JitPack retiré (TM-31) ---
            com.example.stockit.util.CategoryPieView pieChart = findViewById(R.id.pieChart);
            if (pieChart != null && counts != null && !counts.isEmpty()) {
                java.util.List<com.example.stockit.util.CategoryPieView.Slice> entries = new java.util.ArrayList<>();
                int i = 0;
                for (com.example.stockit.model.ProductDao.CategoryCount c : counts) {
                    entries.add(new com.example.stockit.util.CategoryPieView.Slice(
                            com.example.stockit.util.AppLanguage.LV(c.category == null ? "-" : c.category),
                            c.total, com.example.stockit.util.CategoryPieView.PALETTE[i % com.example.stockit.util.CategoryPieView.PALETTE.length]));
                    i++;
                }
                pieChart.setSlices(entries);
            }
        });
    }

    private void refreshAI() {
        controller.getAIInsights(insights -> {
            RecyclerView rv = findViewById(R.id.aiRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new com.example.stockit.controller.AIInsightAdapter(insights));
            }
        });
    }

    private void refreshSuppliers() {
        controller.getSuppliers(s -> {
            RecyclerView rv = findViewById(R.id.supplierRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new SupplierAdapter(s, new SupplierAdapter.OnSupplierActionListener() {
                    @Override public void onEdit(com.example.stockit.model.Supplier sup) { showSupplierForm(sup); }
                    @Override public void onDelete(com.example.stockit.model.Supplier sup) {
                        confirm(L("Supprimer %@ ?", sup.getName()), () -> controller.deleteSupplier(sup, MainActivity.this::refreshSuppliers));
                    }
                    @Override public void onOrder(com.example.stockit.model.Supplier sup) { showPurchaseForm(sup.getName()); }
                }));
            }
        });
    }

    private void showSupplierForm(@androidx.annotation.Nullable com.example.stockit.model.Supplier existing) {
        View v = getLayoutInflater().inflate(R.layout.layout_add_supplier, null);
        EditText n = v.findViewById(R.id.editSupName), e = v.findViewById(R.id.editSupEmail),
                ph = v.findViewById(R.id.editSupPhone), a = v.findViewById(R.id.editSupAddr),
                l = v.findViewById(R.id.editSupLead), di = v.findViewById(R.id.editSupDisc);
        if (existing != null) {
            n.setText(existing.getName()); e.setText(existing.getEmail()); ph.setText(existing.getPhone());
            a.setText(existing.getAddress()); l.setText(String.valueOf(existing.getLeadTime()));
            di.setText(String.valueOf(existing.getDiscount()));
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(existing == null ? L("Nouveau fournisseur") : L("Modifier le fournisseur"))
                .setView(v)
                .setPositiveButton(L("Enregistrer"), (d, w) -> {
                    String name = n.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(this, L("Nom obligatoire"), Toast.LENGTH_SHORT).show(); return; }
                    int lead = parseIntOr(l, 7);
                    double disc;
                    try { disc = Double.parseDouble(di.getText().toString().replace(',', '.')); } catch (Exception ex) { disc = 0; }
                    if (existing == null) {
                        controller.addSupplier(name, e.getText().toString().trim(), ph.getText().toString().trim(),
                                a.getText().toString().trim(), lead, disc, this::refreshSuppliers);
                    } else {
                        existing.setName(name); existing.setEmail(e.getText().toString().trim());
                        existing.setPhone(ph.getText().toString().trim()); existing.setAddress(a.getText().toString().trim());
                        existing.setLeadTime(lead); existing.setDiscount(disc);
                        controller.updateSupplier(existing, this::refreshSuppliers);
                    }
                })
                .setNegativeButton(L("Annuler"), null).show();
    }

    private void refreshPurchases() {
        controller.getPurchaseOrders(o -> {
            RecyclerView rv = findViewById(R.id.purchaseRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new PurchaseOrderAdapter(o, new PurchaseOrderAdapter.OnOrderActionListener() {
                    @Override public void onValidate(com.example.stockit.model.PurchaseOrder order) {
                        confirm(L("Marquer « %@ » comme reçu ?", order.getProductName()), () -> {
                            order.setStatus("Reçu");
                            controller.updatePurchaseOrder(order, () -> {
                                Toast.makeText(MainActivity.this, L("Commande reçue"), Toast.LENGTH_SHORT).show();
                                refreshPurchases();
                            });
                        });
                    }
                    @Override public void onDelete(com.example.stockit.model.PurchaseOrder order) {
                        confirm(L("Supprimer la commande « %@ » ?", order.getProductName()),
                                () -> controller.deletePurchaseOrder(order, MainActivity.this::refreshPurchases));
                    }
                }));
            }
        });
    }

    private void showPurchaseForm(@androidx.annotation.Nullable String supplier) {
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText prod = field(form, "Produit", "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText qty = field(form, "Quantité", "1", android.text.InputType.TYPE_CLASS_NUMBER);
        EditText sup = field(form, "Fournisseur", supplier != null ? supplier : "", android.text.InputType.TYPE_CLASS_TEXT);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L("Nouvelle commande"))
                .setView(form)
                .setPositiveButton(L("Créer"), (d, w) -> {
                    String p = prod.getText().toString().trim();
                    int q = parseIntOr(qty, 0);
                    if (p.isEmpty() || q <= 0) { Toast.makeText(this, L("Produit et quantité requis"), Toast.LENGTH_SHORT).show(); return; }
                    controller.createPurchaseOrder(p, q, sup.getText().toString().trim(), () -> {
                        Toast.makeText(this, L("Commande créée"), Toast.LENGTH_SHORT).show();
                        if (layoutPurchase.getVisibility() == View.VISIBLE) refreshPurchases();
                    });
                })
                .setNegativeButton(L("Annuler"), null).show();
    }

    private static final String[] SHIP_STATUSES = {"PENDING", "PREPARING", "SHIPPED", "DELIVERED"};

    private void refreshShipping() {
        controller.getShippingOrders(o -> {
            RecyclerView rv = findViewById(R.id.shippingRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new ShippingOrderAdapter(o, new ShippingOrderAdapter.OnShippingActionListener() {
                    @Override public void onPrepare(com.example.stockit.model.ShippingOrder order) {
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle(L("Statut de l'expédition"))
                                .setItems(new String[]{L("En attente"), L("En préparation"), L("Expédié"), L("Livré")}, (d, w) -> {
                                    order.setStatus(SHIP_STATUSES[w]);
                                    controller.updateShippingOrder(order, MainActivity.this::refreshShipping);
                                }).show();
                    }
                    @Override public void onLabel(com.example.stockit.model.ShippingOrder order) {
                        EditText input = new EditText(MainActivity.this);
                        input.setHint(L("N° de suivi"));
                        input.setText(order.getTrackingNumber());
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle(L("Étiquette / suivi"))
                                .setView(input)
                                .setPositiveButton(L("Enregistrer"), (d, w) -> {
                                    order.setTrackingNumber(input.getText().toString().trim());
                                    if ("PENDING".equals(order.getStatus()) || "EN ATTENTE".equals(order.getStatus())) order.setStatus("SHIPPED");
                                    controller.updateShippingOrder(order, MainActivity.this::refreshShipping);
                                })
                                .setNegativeButton(L("Annuler"), null).show();
                    }
                    @Override public void onDelete(com.example.stockit.model.ShippingOrder order) {
                        confirm(L("Supprimer l'expédition pour « %@ » ?", order.getCustomerName()),
                                () -> controller.deleteShippingOrder(order, MainActivity.this::refreshShipping));
                    }
                }));
            }
        });
    }

    private void showShippingForm() {
        android.widget.LinearLayout form = new android.widget.LinearLayout(this);
        form.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText cust = field(form, "Destinataire", "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText items = field(form, "Articles (ex: Laptop x2)", "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText addr = field(form, "Adresse", "", android.text.InputType.TYPE_CLASS_TEXT);
        EditText ticket = field(form, "Ticket Jira (optionnel)", "", android.text.InputType.TYPE_CLASS_TEXT);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L("Nouvelle expédition"))
                .setView(form)
                .setPositiveButton(L("Créer"), (d, w) -> {
                    String c = cust.getText().toString().trim();
                    if (c.isEmpty()) { Toast.makeText(this, L("Destinataire requis"), Toast.LENGTH_SHORT).show(); return; }
                    controller.addShippingOrder(c, items.getText().toString().trim(), addr.getText().toString().trim(),
                            ticket.getText().toString().trim().toUpperCase(Locale.ROOT), this::refreshShipping);
                })
                .setNegativeButton(L("Annuler"), null).show();
    }

    private void refreshUsers() {
        controller.getUsers(u -> {
            RecyclerView rv = findViewById(R.id.userRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new UserAdapter(u, this::showUserActions, user -> {
                    if (!controller.isAdmin()) { Toast.makeText(this, L("Permission refusée : rôle ADMIN requis"), Toast.LENGTH_LONG).show(); return; }
                    confirm(L("Supprimer l'utilisateur %@ ?", user.getUsername()), () -> controller.deleteUser(user, this::refreshUsers));
                }));
            }
        });
    }

    private void showUserActions(com.example.stockit.model.User user) {
        String info = L("Rôle : %@\nNiveau %@ · %@ points", user.getRole(), user.getLevel(), user.getPoints())
                + (user.getBadges() != null && !user.getBadges().isEmpty() ? L("\nBadges : %@", com.example.stockit.util.AppLanguage.LV(user.getBadges())) : "");
        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(user.getUsername()).setMessage(info).setPositiveButton(L("Fermer"), null);
        if (controller.isAdmin()) {
            b.setNegativeButton(L("Supprimer"), (d, w) ->
                    confirm(L("Supprimer l'utilisateur %@ ?", user.getUsername()), () -> controller.deleteUser(user, this::refreshUsers)));
        }
        b.show();
    }

    // --- Catégories (iOS CategoriesView) ---
    private void setupCategoryForm() {
        View btn = findViewById(R.id.btnAddCat);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            EditText name = findViewById(R.id.inputCatName);
            EditText color = findViewById(R.id.inputCatColor);
            android.widget.Spinner parent = findViewById(R.id.spinnerParentCat);
            String n = name.getText().toString().trim();
            if (n.isEmpty()) { Toast.makeText(this, L("Nom de catégorie requis"), Toast.LENGTH_SHORT).show(); return; }
            String c = color.getText().toString().trim();
            if (!c.matches("#[0-9A-Fa-f]{6}")) c = "#0F53FA";
            Integer parentId = null;
            Object sel = parent.getSelectedItem();
            if (sel instanceof com.example.stockit.model.Category) parentId = ((com.example.stockit.model.Category) sel).getId();
            controller.addCategory(n, parentId, c, () -> {
                name.setText("");
                Toast.makeText(this, L("Catégorie créée"), Toast.LENGTH_SHORT).show();
                refreshCategories();
            });
        });
    }

    private void refreshCategories() {
        controller.getCategories(cats -> {
            RecyclerView rv = findViewById(R.id.categoryRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new CategoryAdapter(cats, new CategoryAdapter.OnCategoryActionListener() {
                    @Override public void onEdit(com.example.stockit.model.Category c) {
                        if (!controller.isAdmin()) return;
                        android.widget.LinearLayout form = new android.widget.LinearLayout(MainActivity.this);
                        form.setOrientation(android.widget.LinearLayout.VERTICAL);
                        int pad = (int) (20 * getResources().getDisplayMetrics().density);
                        form.setPadding(pad, pad / 2, pad, 0);
                        EditText n = field(form, "Nom", c.getName(), android.text.InputType.TYPE_CLASS_TEXT);
                        EditText col = field(form, "Couleur (#RRGGBB)", c.getLabelColor(), android.text.InputType.TYPE_CLASS_TEXT);
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this).setTitle(L("Modifier la catégorie")).setView(form)
                                .setPositiveButton(L("Enregistrer"), (d, w) -> {
                                    c.setName(n.getText().toString().trim());
                                    String cc = col.getText().toString().trim();
                                    if (cc.matches("#[0-9A-Fa-f]{6}")) c.setLabelColor(cc);
                                    controller.updateCategory(c, MainActivity.this::refreshCategories);
                                })
                                .setNegativeButton(L("Annuler"), null).show();
                    }
                    @Override public void onDelete(com.example.stockit.model.Category c) {
                        if (!controller.isAdmin()) { Toast.makeText(MainActivity.this, L("Permission refusée : rôle ADMIN requis"), Toast.LENGTH_LONG).show(); return; }
                        confirm(L("Supprimer la catégorie %@ ?", c.getName()), () -> controller.deleteCategory(c, MainActivity.this::refreshCategories));
                    }
                }));
            }
            android.widget.Spinner parent = findViewById(R.id.spinnerParentCat);
            if (parent != null) {
                List<Object> items = new ArrayList<>();
                items.add(L("— Aucune —"));
                items.addAll(cats);
                parent.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
            }
        });
    }

    private void setupCrudFabs() {
        View fs = findViewById(R.id.fabAddSupplier);
        if (fs != null) fs.setOnClickListener(v -> showSupplierForm(null));
        View fp = findViewById(R.id.fabAddPurchase);
        if (fp != null) fp.setOnClickListener(v -> showPurchaseForm(null));
        View fsh = findViewById(R.id.fabAddShipping);
        if (fsh != null) fsh.setOnClickListener(v -> showShippingForm());
    }

    private void setupReportsButtons() {
        View exp = findViewById(R.id.btnExportReport);
        if (exp != null) exp.setOnClickListener(v -> exportCsv());
        View ai = findViewById(R.id.btnGenerateAiReport);
        if (ai != null) ai.setOnClickListener(v -> generateMonthlyPdfReport());
    }

    /** Export CSV de l'inventaire (iOS : ShareLink « EXPORTER EN EXCEL (CSV) »). */
    private void exportCsv() {
        controller.buildStockCsv(csv -> {
            try {
                File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                File f = new File(dir, "StockIT_inventaire.csv");
                try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                    w.write('\uFEFF'); // BOM : Excel détecte l'UTF-8 (accents)
                    w.write(csv);
                }
                Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.example.stockit.fileprovider", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/csv");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.putExtra(Intent.EXTRA_SUBJECT, "StockIT — inventaire");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                com.example.stockit.util.AppSession.allowExternal();
                startActivity(Intent.createChooser(i, L("Exporter l'inventaire (CSV)")));
            } catch (Exception e) {
                Toast.makeText(this, L("Export impossible : %@", e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showAllMovements() {
        controller.getStockMovements(movements -> {
            Collections.reverse(movements);
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new StockMovementAdapter(movements));
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(L("Historique des mouvements (%@)", movements.size()))
                    .setView(rv).setPositiveButton("OK", null).show();
        });
    }

    private void confirm(String message, Runnable action) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(L("Confirmer"), (d, w) -> action.run())
                .setNegativeButton(L("Annuler"), null).show();
    }

    /** Nom / rôle de l'utilisateur dans l'en-tête du drawer. */
    private void applyNavHeader() {
        NavigationView nv = findViewById(R.id.nav_view);
        if (nv == null || nv.getHeaderCount() == 0) return;
        com.example.stockit.model.User u = MainController.getCurrentUser();
        android.widget.TextView name = nv.getHeaderView(0).findViewById(R.id.nav_user_name);
        if (name != null && u != null) name.setText(u.getUsername());
    }

    /**
     * Droits identiques à iOS : Catégories, Audit et Utilisateurs sont consultables par tous ;
     * seules les actions (créer / modifier / supprimer) sont réservées aux administrateurs.
     */
    private void applyRoleVisibility(NavigationView nv) {
        if (nv == null) return;
        boolean admin = controller.isAdmin();
        View catForm = findViewById(R.id.btnAddCat);
        if (catForm != null) {
            View card = (View) catForm.getParent().getParent();
            if (card != null) card.setVisibility(admin ? View.VISIBLE : View.GONE);
        }
        // Gestion Articles : création / édition réservées ADMIN / MANAGER (iOS : bouton + du Stock).
        MenuItem art = nv.getMenu().findItem(R.id.nav_article);
        if (art != null) art.setVisible(controller.canEditStock());
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9001);
        }
    }

    private void refreshAudit() {
        controller.getAuditLogs(l -> {
            RecyclerView rv = findViewById(R.id.auditRecyclerView);
            if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new AuditLogAdapter(l)); }
        });
    }

    private void refreshLeaderboard() {
        controller.getLeaderboard(l -> {
            RecyclerView rv = findViewById(R.id.leaderboardRecyclerView);
            if (rv == null) return;
            List<com.example.stockit.controller.SimpleRowAdapter.Row> rows = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                com.example.stockit.model.User u = l.get(i);
                String medal = i == 0 ? "🥇 " : i == 1 ? "🥈 " : i == 2 ? "🥉 " : (i + 1) + ". ";
                String badges = u.getBadges() == null || u.getBadges().isEmpty() ? "" : " · " + com.example.stockit.util.AppLanguage.LV(u.getBadges());
                rows.add(new com.example.stockit.controller.SimpleRowAdapter.Row(medal + u.getUsername(),
                        L("Niveau %@", u.getLevel()) + badges, L("%@ pts", u.getPoints()), 0, u));
            }
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new com.example.stockit.controller.SimpleRowAdapter(rows, null));
        });
    }

    private void generateMonthlyPdfReport() {
        final androidx.appcompat.app.AlertDialog loading = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(L("Rédaction du rapport par l'IA (Claude)…")).setCancelable(false).show();

        controller.getReportData((total, low, value, out, counts) -> {
            String rawData = String.format(Locale.FRANCE,
                "Inventaire Vistaprint : %d articles, %d en rupture, %d stock bas. Valeur : %.2f euros.",
                total, out, low, value);
            
            controller.generateAIReport(rawData, summary -> {
                loading.dismiss();
                File pdfFile = com.example.stockit.util.PdfReportGenerator.generateMonthlyReport(this, summary);
                if (pdfFile != null) {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(L("Rapport IA Terminé"))
                            .setMessage(L("Le rapport a été rédigé par l'IA et sauvegardé.\nVoulez-vous le partager ?"))
                            .setPositiveButton(L("Partager"), (d, w) -> {
                                Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.example.stockit.fileprovider", pdfFile);
                                Intent intent = new Intent(Intent.ACTION_SEND);
                                intent.setType("application/pdf");
                                intent.putExtra(Intent.EXTRA_STREAM, uri);
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                com.example.stockit.util.AppSession.allowExternal();
                                startActivity(Intent.createChooser(intent, L("Partager le rapport")));
                            })
                            .setNegativeButton(L("Fermer"), null).show();
                } else {
                    Toast.makeText(this, L("Erreur de génération PDF"), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ================================================================
    //  Vista dashboard helpers — greeting + KPI counter animations
    // ================================================================

    /** Reprend le nom du header du drawer pour saluer l'utilisateur. */
    private void applyHeroGreeting() {
        android.widget.TextView greeting = findViewById(R.id.dashHeroGreeting);
        android.widget.TextView avatar   = findViewById(R.id.dashHeroAvatar);
        if (greeting == null && avatar == null) return;

        String userName = "Technician";
        com.example.stockit.model.User cu = MainController.getCurrentUser();
        if (cu != null && cu.getUsername() != null && !cu.getUsername().isEmpty()) userName = cu.getUsername();
        View header = ((NavigationView) findViewById(R.id.nav_view)).getHeaderView(0);
        if (header != null) {
            android.widget.TextView navName = header.findViewById(R.id.nav_user_name);
            if (navName != null && navName.getText() != null && navName.getText().length() > 0) {
                String n = navName.getText().toString().trim();
                if (cu == null && !n.isEmpty() && !"User Name".equalsIgnoreCase(n)) userName = n;
            }
        }

        // Salutation contextuelle (matin / après-midi / soir)
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String hello = hour < 12 ? L("Bonjour") : hour < 18 ? L("Bon après-midi") : L("Bonsoir");

        if (greeting != null) greeting.setText(L("%@ %@ 👋", hello, userName));
        if (avatar != null) {
            String initial = userName.substring(0, 1).toUpperCase(Locale.getDefault());
            avatar.setText(initial);
        }
    }

    /** Compteur animé pour un entier (0 → target) sur 900 ms. */
    private void animateInt(final android.widget.TextView tv, int target, String suffix) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(0, target);
        a.setDuration(900);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(v -> {
            int val = (int) v.getAnimatedValue();
            tv.setText(suffix == null ? String.valueOf(val) : val + suffix);
        });
        a.start();
    }

    /** Compteur animé pour un double (0.0 → target) formaté avec 2 décimales. */
    private void animateDouble(final android.widget.TextView tv, double target, String suffix) {
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, (float) target);
        a.setDuration(1100);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        String sfx = suffix == null ? "" : suffix;
        a.addUpdateListener(v -> {
            float val = (float) v.getAnimatedValue();
            tv.setText(String.format(Locale.getDefault(), "%.2f", val) + sfx);
        });
        a.start();
    }

    /**
     * Mini sparkline turquoise dans le hero — 7 derniers points d'évolution du stock
     * (placeholder si pas assez de données). Pas d'axes, pas de légende : juste la ligne.
     * Vue maison (JitPack / MPAndroidChart retiré, TM-31).
     */
    private void renderHeroChart() {
        final com.example.stockit.util.SparkLineView chart = findViewById(R.id.dashHeroChart);
        if (chart == null) return;
        int turquoise = androidx.core.content.ContextCompat.getColor(this, R.color.vista_turquoise);
        chart.setStrokeColor(turquoise);

        controller.getStockMovements(movements -> {
            java.util.List<Float> pts = new java.util.ArrayList<>();
            int n = Math.min(7, movements.size());
            if (n < 2) {
                // Placeholder décoratif si trop peu de mouvements réels
                float[] seed = {2, 3.2f, 2.8f, 4.1f, 3.6f, 5.0f, 4.7f};
                for (float v : seed) pts.add(v);
            } else {
                for (int i = 0; i < n; i++) {
                    pts.add((float) Math.abs(movements.get(movements.size() - n + i).getQuantity()));
                }
            }
            chart.setPoints(pts);
        });
    }
}
