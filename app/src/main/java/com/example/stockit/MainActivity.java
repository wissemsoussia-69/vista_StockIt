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
                if (codes != null) {
                    for (String code : codes) {
                        controller.addProduct("Produit " + code, "Batch", null, "Batch Scan", code, 1, 0.0, "", "", "Batch", () -> {});
                    }
                    refreshList();
                    updateDashboardData();
                }
            }
        });

        setupSearch();
        findViewById(R.id.refreshButton).setOnClickListener(v -> refreshList());
        
        showDashboard();

        // Seed initial data if empty
        controller.getStock(products -> {
            if (products.isEmpty()) {
                controller.seedStock(this::updateDashboardData);
            }
        });
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
            findViewById(R.id.btnQuickAdd).setOnClickListener(v -> { showLayout(layoutArticle); refreshArticles(); });
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

    private void startBatchScan() {
        batchScanLauncher.launch(new Intent(this, BatchScanActivity.class));
    }

    private void startScanIA() {
        try {
            File photoFile = File.createTempFile("SCAN_", ".jpg", getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES));
            photoUri = androidx.core.content.FileProvider.getUriForFile(this, "com.example.stockit.fileprovider", photoFile);
            takePictureLauncher.launch(photoUri);
        } catch (Exception e) { Toast.makeText(this, "Erreur caméra", Toast.LENGTH_SHORT).show(); }
    }

    private void processImageFromUri(Uri uri) {
        final androidx.appcompat.app.AlertDialog progress = new androidx.appcompat.app.AlertDialog.Builder(this).setMessage("Analyse Vision Gemini...").setCancelable(false).show();
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
            String optiText = String.format(Locale.getDefault(), "\n\n⚡ Optimisation : %.0f%% d'espace gagné (Format WebP)", saved > 0 ? saved : 85);

            String displayMsg = "Matériel détecté par Gemini : " + label + optiText;

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Analyse Vision Gemini 1.5")
                    .setMessage(displayMsg)
                    .setPositiveButton("Ajouter", (d, w) -> {
                        controller.addProduct(label, "Informatique", null, "Scan Gemini Vision", "ASSET-" + System.currentTimeMillis(), 1, 0.0, "", "", "Scan IA", () -> {
                            refreshList();
                            updateDashboardData();
                            Toast.makeText(this, label + " ajouté !", Toast.LENGTH_SHORT).show();
                            
                            // --- COORDINATION PFE STOCKIT <-> SUPPORT ---
                            triggerCoordinationSimulation(label);
                        });
                    })
                    .setNegativeButton("Réessayer", (d, w) -> startScanIA())
                    .show();
        });
    }

    private void triggerCoordinationSimulation(String assetLabel) {
        com.example.stockit.controller.AssetTicketCoordinator coordinator = new com.example.stockit.controller.AssetTicketCoordinator(this);
        
        // 1. On définit le matériel scanné (votre module)
        com.example.stockit.controller.AssetTicketCoordinator.ITAsset asset = 
            new com.example.stockit.controller.AssetTicketCoordinator.ITAsset("ID-123", assetLabel, "Modèle standard", "SN-9876");

        // 2. On définit les tickets ouverts (module support)
        java.util.List<com.example.stockit.controller.AssetTicketCoordinator.Ticket> tickets = new java.util.ArrayList<>();
        tickets.add(new com.example.stockit.controller.AssetTicketCoordinator.Ticket("TK-001", "Écran cassé CEO", "Écran", "CRITIQUE", System.currentTimeMillis()));
        tickets.add(new com.example.stockit.controller.AssetTicketCoordinator.Ticket("TK-002", "Laptop lent Thomas", "Ordinateur", "HAUTE", System.currentTimeMillis() + 3600000));
        tickets.add(new com.example.stockit.controller.AssetTicketCoordinator.Ticket("TK-003", "Besoin souris Sarah", "Souris", "BASSE", System.currentTimeMillis() + 86400000));

        // 3. On récupère le technicien connecté
        com.example.stockit.model.User currentUser = com.example.stockit.controller.MainController.getCurrentUser();
        String techName = (currentUser != null) ? currentUser.getUsername() : "Nadhem";
        com.example.stockit.controller.AssetTicketCoordinator.Technician tech = 
            new com.example.stockit.controller.AssetTicketCoordinator.Technician(techName, "EMP-01");

        // 4. On lance l'algorithme de coordination
        coordinator.coordinateAssetReceipt(asset, tickets, tech);
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
            if (txtAlerts != null) txtAlerts.setText(out + " en rupture, " + low + " bas");
            if (txtHeroSub != null) txtHeroSub.setText(
                    total + " articles · " + out + " en rupture");
        });

        // --- QUÊTES (LUDIFICATION) ---
        controller.getQuests(quests -> {
            if (!quests.isEmpty()) {
                com.example.stockit.model.Quest q = quests.get(0);
                android.widget.TextView title = findViewById(R.id.dashQuestTitle);
                android.widget.TextView desc = findViewById(R.id.dashQuestDesc);
                ProgressBar prog = findViewById(R.id.dashQuestProgress);
                if (title != null) title.setText(q.getTitle());
                if (desc != null) desc.setText(q.getDescription() + " (" + q.getCurrentCount() + "/" + q.getGoalCount() + ")");
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

    private void refreshList() {
        controller.getStock(products -> {
            RecyclerView rv = findViewById(R.id.recyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new ProductAdapter(products, productActionListener));
            }
        });
    }

    private final ProductAdapter.OnProductActionListener productActionListener = new ProductAdapter.OnProductActionListener() {
        @Override public void onUpdateQuantity(Product p, int d) { 
            controller.updateQuantity(p, d, () -> { refreshList(); updateDashboardData(); }); 
        }
        @Override public void onShowDetails(Product p) { showProductDetails(p); }
        @Override public void onShowMovement(Product p) { showProductTimeline(p); }
    };

    private void showProductDetails(Product p) {
        View view = getLayoutInflater().inflate(R.layout.dialog_product_details, null);
        ((android.widget.TextView)view.findViewById(R.id.detailName)).setText(p.getName());
        ((android.widget.TextView)view.findViewById(R.id.detailStock)).setText("Stock actuel : " + p.getQuantity());
        
        // Kit Logic
        android.widget.TextView txtKit = view.findViewById(R.id.detailKit);
        if (p.getName().toLowerCase().contains("écran") || p.getName().toLowerCase().contains("monitor")) {
            txtKit.setText("⚠️ KIT : Câble HDMI + Alimentation");
            txtKit.setVisibility(View.VISIBLE);
        }

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setView(view).create();
        view.findViewById(R.id.btnDetailChrono).setOnClickListener(v -> { dialog.dismiss(); showProductTimeline(p); });
        view.findViewById(R.id.btnDetailZycus).setOnClickListener(v -> {
            dialog.dismiss();
            controller.createZycusOrder(p, 5, pr -> Toast.makeText(this, "PR Créée : " + pr, Toast.LENGTH_LONG).show());
        });
        view.findViewById(R.id.btnDetailAlert).setOnClickListener(v -> {
            dialog.dismiss();
            controller.triggerN8nAlert(p, () -> Toast.makeText(this, "Alerte envoyée !", Toast.LENGTH_SHORT).show());
        });
        dialog.show();
    }

    private void showProductTimeline(Product p) {
        controller.getProductMovements(p.getId(), movements -> {
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new com.example.stockit.controller.TimelineAdapter(movements));
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Timeline : " + p.getName()).setView(rv).setPositiveButton("OK", null).show();
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
                Toast.makeText(this, "Veuillez remplir le nom et la quantité", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int q = Integer.parseInt(qtyS);
                double p = Double.parseDouble(priceS);
                
                controller.addProduct(name, "Informatique", null, "Saisie manuelle", "ASSET-" + System.currentTimeMillis(), q, p, "", "", reason, () -> {
                    Toast.makeText(this, "Article " + name + " ajouté avec succès !", Toast.LENGTH_SHORT).show();
                    if (inputName != null) inputName.setText("");
                    if (inputQty != null) inputQty.setText("");
                    if (inputPrice != null) inputPrice.setText("");
                    if (inputReason != null) inputReason.setText("");
                    refreshArticles();
                    updateDashboardData();
                });
            } catch (Exception e) {
                Toast.makeText(this, "Erreur de format (Nombre attendu)", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshArticles() {
        controller.getStock(products -> {
            RecyclerView rv = findViewById(R.id.articleRecyclerView);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                rv.setAdapter(new com.example.stockit.controller.ArticleAdapter(products, new com.example.stockit.controller.ArticleAdapter.OnArticleActionListener() {
                    @Override public void onEdit(Product p) {}
                    @Override public void onDelete(Product p) { controller.deleteProduct(p, () -> refreshArticles()); }
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
                controller.searchProducts(q, products -> {
                    RecyclerView rv = findViewById(R.id.recyclerView);
                    if (rv != null) rv.setAdapter(new ProductAdapter(products, productActionListener));
                });
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
            if (tvOut != null) tvOut.setText(out + " articles");
            
            android.widget.TextView tvItems = findViewById(R.id.reportTotalItems);
            if (tvItems != null) tvItems.setText("Articles en stock: " + total);

            // --- PIE CHART (Distribution) ---
            com.github.mikephil.charting.charts.PieChart pieChart = findViewById(R.id.pieChart);
            if (pieChart != null && counts != null && !counts.isEmpty()) {
                java.util.List<com.github.mikephil.charting.data.PieEntry> entries = new java.util.ArrayList<>();
                for (com.example.stockit.model.ProductDao.CategoryCount c : counts) {
                    entries.add(new com.github.mikephil.charting.data.PieEntry(c.total, c.category));
                }
                com.github.mikephil.charting.data.PieDataSet dataSet = new com.github.mikephil.charting.data.PieDataSet(entries, "Catégories");
                dataSet.setColors(com.github.mikephil.charting.utils.ColorTemplate.MATERIAL_COLORS);
                com.github.mikephil.charting.data.PieData data = new com.github.mikephil.charting.data.PieData(dataSet);
                pieChart.setData(data);
                pieChart.invalidate();
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
            if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new SupplierAdapter(s, x->{}, x->{})); }
        });
    }

    private void refreshPurchases() {
        controller.getPurchaseOrders(o -> {
            RecyclerView rv = findViewById(R.id.purchaseRecyclerView);
            if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new PurchaseOrderAdapter(o, x->{})); }
        });
    }

    private void refreshShipping() {
        controller.getShippingOrders(o -> {
            RecyclerView rv = findViewById(R.id.shippingRecyclerView);
            if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new ShippingOrderAdapter(o, null)); }
        });
    }

    private void refreshUsers() {
        controller.getUsers(u -> {
            RecyclerView rv = findViewById(R.id.userRecyclerView);
            if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new UserAdapter(u, x->{}, x->{})); }
        });
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
            if (rv != null) { rv.setLayoutManager(new LinearLayoutManager(this)); rv.setAdapter(new UserAdapter(l, x->{}, x->{})); }
        });
    }

    private void generateMonthlyPdfReport() {
        final androidx.appcompat.app.AlertDialog loading = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Rédaction du rapport par l'IA Gemini...").setCancelable(false).show();

        controller.getReportData((total, low, value, out, counts) -> {
            String rawData = String.format(Locale.getDefault(), 
                "Inventaire Vistaprint : %d articles, %d en rupture, %d stock bas. Valeur : %.2f euros.",
                total, out, low, value);
            
            controller.generateAIReport(rawData, summary -> {
                loading.dismiss();
                File pdfFile = com.example.stockit.util.PdfReportGenerator.generateMonthlyReport(this, summary);
                if (pdfFile != null) {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Rapport IA Terminé")
                            .setMessage("Le rapport a été rédigé par Gemini et sauvegardé.\nVoulez-vous le partager ?")
                            .setPositiveButton("Partager", (d, w) -> {
                                Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.example.stockit.fileprovider", pdfFile);
                                Intent intent = new Intent(Intent.ACTION_SEND);
                                intent.setType("application/pdf");
                                intent.putExtra(Intent.EXTRA_STREAM, uri);
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(intent, "Partager le rapport"));
                            })
                            .setNegativeButton("Fermer", null).show();
                } else {
                    Toast.makeText(this, "Erreur de génération PDF", Toast.LENGTH_SHORT).show();
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
        View header = ((NavigationView) findViewById(R.id.nav_view)).getHeaderView(0);
        if (header != null) {
            android.widget.TextView navName = header.findViewById(R.id.nav_user_name);
            if (navName != null && navName.getText() != null && navName.getText().length() > 0) {
                String n = navName.getText().toString().trim();
                if (!n.isEmpty() && !"User Name".equalsIgnoreCase(n)) userName = n;
            }
        }

        // Salutation contextuelle (matin / après-midi / soir)
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String hello = hour < 12 ? "Bonjour" : hour < 18 ? "Bon après-midi" : "Bonsoir";

        if (greeting != null) greeting.setText(hello + " " + userName + " 👋");
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
     */
    private void renderHeroChart() {
        final com.github.mikephil.charting.charts.LineChart chart = findViewById(R.id.dashHeroChart);
        if (chart == null) return;

        controller.getStockMovements(movements -> {
            java.util.List<com.github.mikephil.charting.data.Entry> entries = new java.util.ArrayList<>();
            int n = Math.min(7, movements.size());
            if (n < 2) {
                // Placeholder décoratif si trop peu de mouvements réels
                float[] seed = {2, 3.2f, 2.8f, 4.1f, 3.6f, 5.0f, 4.7f};
                for (int i = 0; i < seed.length; i++) entries.add(
                        new com.github.mikephil.charting.data.Entry(i, seed[i]));
            } else {
                for (int i = 0; i < n; i++) {
                    com.example.stockit.model.StockMovement m = movements.get(movements.size() - n + i);
                    entries.add(new com.github.mikephil.charting.data.Entry(i, Math.abs(m.getQuantity())));
                }
            }

            int turquoise = androidx.core.content.ContextCompat.getColor(this, R.color.vista_turquoise);
            com.github.mikephil.charting.data.LineDataSet set =
                    new com.github.mikephil.charting.data.LineDataSet(entries, "Stock");
            set.setColor(turquoise);
            set.setLineWidth(2f);
            set.setDrawCircles(false);
            set.setDrawValues(false);
            set.setMode(com.github.mikephil.charting.data.LineDataSet.Mode.CUBIC_BEZIER);
            set.setDrawFilled(true);
            set.setFillColor(turquoise);
            set.setFillAlpha(60);

            chart.setData(new com.github.mikephil.charting.data.LineData(set));
            chart.getDescription().setEnabled(false);
            chart.getLegend().setEnabled(false);
            chart.getXAxis().setEnabled(false);
            chart.getAxisLeft().setEnabled(false);
            chart.getAxisRight().setEnabled(false);
            chart.setDrawGridBackground(false);
            chart.setDrawBorders(false);
            chart.setTouchEnabled(false);
            chart.setViewPortOffsets(0, 0, 0, 0);
            chart.animateX(900);
            chart.invalidate();
        });
    }
}
