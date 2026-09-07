package com.example.stockit;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

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
import com.auth0.android.Auth0;
import com.auth0.android.authentication.AuthenticationAPIClient;
import com.auth0.android.authentication.AuthenticationException;
import com.auth0.android.authentication.storage.CredentialsManagerException;
import com.auth0.android.authentication.storage.SecureCredentialsManager;
import com.auth0.android.authentication.storage.SharedPreferencesStorage;
import com.auth0.android.callback.Callback;
import com.auth0.android.provider.WebAuthProvider;
import com.auth0.android.result.Credentials;
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

    private Auth0 auth0Account;
    private SecureCredentialsManager secureCredentialsManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initAuth0();
        checkExistingSession();

        controller = MainController.getInstance(this);
        drawerLayout = findViewById(R.id.drawer_layout);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.open_drawer, R.string.close_drawer);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        populateDrawerHeader(navigationView);

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
                        controller.addProduct("Product " + code, "Batch", null, "Batch Scan", code, 1, 0.0, "", "", "Batch", () -> {});
                    }
                    refreshList();
                    updateDashboardData();
                }
            }
        });

        setupSearch();
        findViewById(R.id.refreshButton).setOnClickListener(v -> refreshList());
        
        showDashboard();

        controller.getStock(products -> {
            if (products.isEmpty()) {
                controller.seedStock(this::updateDashboardData);
            }
        });

        com.example.stockit.util.WeeklyDigestWorker.schedule(this);
        com.example.stockit.util.AnalyticsSyncWorker.schedule(this);
    }

    private void initAuth0() {
        String clientId = getString(R.string.com_auth0_client_id);
        String domain = getString(R.string.com_auth0_domain);
        if (TextUtils.isEmpty(clientId) || TextUtils.isEmpty(domain)) {
            Log.e("AUTH_ERROR", "Auth0 credentials missing in values/auth0.xml");
            return;
        }
        auth0Account = new Auth0(clientId, domain);
        secureCredentialsManager = new SecureCredentialsManager(
                this,
            new AuthenticationAPIClient(auth0Account),
                new SharedPreferencesStorage(this)
        );
    }

    private void checkExistingSession() {
        if (secureCredentialsManager == null) {
            return;
        }
        try {
            if (secureCredentialsManager.hasValidCredentials()) {
                Log.d("AUTH_SUCCESS", "Existing session detected (valid credentials). Login not restarted.");
            } else {
                Log.d("AUTH_ERROR", "No valid session found. Login required.");
            }
        } catch (Throwable t) {
            Log.e("AUTH_ERROR", "Session check error: " + t.getMessage(), t);
        }
    }

    private void login() {
        if (auth0Account == null || secureCredentialsManager == null) {
            Log.e("AUTH_ERROR", "Auth0 not initialized. Check values/auth0.xml.");
            return;
        }

        WebAuthProvider.login(auth0Account)
                .withScheme(getString(R.string.com_auth0_scheme))
                .withConnection(BuildConfig.AUTH0_CONNECTION_NAME)
                .withScope("openid profile email")
                .start(this, new Callback<Credentials, AuthenticationException>() {
                    @Override
                    public void onSuccess(Credentials credentials) {
                        try {
                            secureCredentialsManager.saveCredentials(credentials);
                        } catch (Throwable t) {
                            Log.e("AUTH_ERROR", "saveCredentials failed: " + t.getMessage(), t);
                        }

                        String token = credentials.getAccessToken();
                        String preview = (token != null && token.length() > 20)
                                ? token.substring(0, 20) + "..."
                                : String.valueOf(token);
                        Log.d("AUTH_SUCCESS", "Token received (preview): " + preview);
                    }

                    @Override
                    public void onFailure(@NonNull AuthenticationException error) {
                        String message = error.getMessage();
                        String description = error.getDescription();
                        String code = error.getCode();
                        Log.e("AUTH_ERROR",
                                "Login failed | code=" + code
                                        + " | message=" + message
                                        + " | description=" + description,
                                error);
                    }
                });
    }

    private void logout() {
        logout(null);
    }

    private void logout(@androidx.annotation.Nullable Runnable onComplete) {
        if (auth0Account == null || secureCredentialsManager == null) {
            Log.e("AUTH_ERROR", "Logout not possible: Auth0 not initialized.");
            if (onComplete != null) onComplete.run();
            return;
        }

        WebAuthProvider.logout(auth0Account)
                .withScheme(getString(R.string.com_auth0_scheme))
                .start(this, new Callback<Void, AuthenticationException>() {
                    @Override
                    public void onSuccess(Void payload) {
                        secureCredentialsManager.clearCredentials();
                        Log.d("AUTH_SUCCESS", "Logout successful, local credentials cleared.");
                        if (onComplete != null) onComplete.run();
                    }

                    @Override
                    public void onFailure(@NonNull AuthenticationException error) {
                        Log.e("AUTH_ERROR", "Logout failed: " + error.getMessage(), error);
                        if (onComplete != null) onComplete.run();
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
        if (findViewById(R.id.btnQuickAdd) != null)
            findViewById(R.id.btnQuickAdd).setOnClickListener(v -> { showLayout(layoutArticle); refreshArticles(); });
        if (findViewById(R.id.btnNewMovement) != null)
            findViewById(R.id.btnNewMovement).setOnClickListener(v -> { showLayout(layoutStock); refreshList(); });
        if (findViewById(R.id.btnVoiceControl) != null)
            findViewById(R.id.btnVoiceControl).setOnClickListener(v -> startActivity(new Intent(this, AIChatActivity.class)));
        if (findViewById(R.id.btnViewAllMovements) != null)
            findViewById(R.id.btnViewAllMovements).setOnClickListener(v -> { showLayout(layoutStock); refreshList(); });
        if (findViewById(R.id.btnScanInventory) != null)
            findViewById(R.id.btnScanInventory).setOnClickListener(v -> showScanModeDialog());
        if (findViewById(R.id.btnMovementHistory) != null)
            findViewById(R.id.btnMovementHistory).setOnClickListener(v -> { showLayout(layoutStock); refreshList(); });
    }

    private void showScanModeDialog() {
        String[] options = {
                "Quick AI scan",
                "Batch barcode scan",
                "Full Stock In scan"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Choose scan mode")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        startScanIA();
                    } else if (which == 1) {
                        startBatchScan();
                    } else {
                        startActivity(new Intent(this, ScanAssetActivity.class));
                    }
                })
                .show();
    }

    private boolean fabMenuOpen = false;

    private void setupVistaFab() {
        final com.google.android.material.floatingactionbutton.FloatingActionButton fab =
                findViewById(R.id.fabVistaCentral);
        final View miniMenu = findViewById(R.id.fabMiniMenu);
        if (fab == null || miniMenu == null) return;

        View scanMini = findViewById(R.id.fabActionScan);
        if (scanMini != null) scanMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startActivity(new Intent(this, ScanAssetActivity.class));
        });

        View receiveMini = findViewById(R.id.fabActionReceive);
        if (receiveMini != null) receiveMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startActivity(new Intent(this, ReceivePackageActivity.class));
        });

        View shipMini = findViewById(R.id.fabActionShipOut);
        if (shipMini != null) shipMini.setOnClickListener(v -> {
            toggleFabMenu(fab, miniMenu, false);
            startActivity(new Intent(this, ScanOutActivity.class));
        });

        fab.setOnClickListener(v -> toggleFabMenu(fab, miniMenu, !fabMenuOpen));
    }

    private void toggleFabMenu(View fab, View miniMenu, boolean open) {
        fabMenuOpen = open;

        fab.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);

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

    private void setupAlertPulse() {
        View dot = findViewById(R.id.dashAlertPulse);
        if (dot != null) {
            dot.startAnimation(android.view.animation.AnimationUtils
                    .loadAnimation(this, R.anim.pulse_urgency));
        }
    }

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
        } catch (Exception e) { Toast.makeText(this, R.string.toast_camera_error, Toast.LENGTH_SHORT).show(); }
    }

    private void processImageFromUri(Uri uri) {
        final androidx.appcompat.app.AlertDialog progress = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.dlg_msg_vision_analyzing).setCancelable(false).show();
        new Thread(() -> {
            byte[] jpeg;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                jpeg = buf.toByteArray();
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Toast.makeText(this, R.string.toast_read_image_error, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            com.example.stockit.util.GeminiGatewayClient.identify(this, jpeg, (name, error) -> runOnUiThread(() -> {
                progress.dismiss();
                if (error != null || name == null || name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_analysis_failed,
                            error != null ? error : "no result"), Toast.LENGTH_LONG).show();
                    return;
                }
                traiterResultatIA(name, name, uri);
            }));
        }, "scan-ia-quick").start();
    }

    private void traiterResultatIA(String label, String visionText, Uri uri) {
        com.example.stockit.util.ImageOptimizerUtil.optimizeImage(this, uri, (optimizedFile, oldSize, newSize) -> {
            double saved = (1.0 - (double)newSize / (oldSize > 0 ? oldSize : 1000000)) * 100;
            String optiText = String.format(Locale.getDefault(), "\n\nOptimization: %.0f%% space saved (WebP format)", saved > 0 ? saved : 85);

            String displayMsg = "Hardware detected by Gemini: " + label + optiText;

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dlg_title_scan_vision)
                    .setMessage(displayMsg)
                    .setPositiveButton(R.string.action_add, (d, w) -> {
                        controller.addProduct(label, "IT", null, "Gemini Vision Scan", "ASSET-" + System.currentTimeMillis(), 1, 0.0, "", "", "AI scan", () -> {
                            refreshList();
                            updateDashboardData();
                            Toast.makeText(this, getString(R.string.toast_added_success, label), Toast.LENGTH_SHORT).show();
                            
                            triggerCoordinationSimulation(label);
                        });
                    })
                    .setNegativeButton(R.string.action_retry, (d, w) -> startScanIA())
                    .show();
        });
    }

    private void triggerCoordinationSimulation(String assetLabel) {
        com.example.stockit.controller.AssetTicketCoordinator coordinator = new com.example.stockit.controller.AssetTicketCoordinator(this);
        
        com.example.stockit.controller.AssetTicketCoordinator.ITAsset asset = 
            new com.example.stockit.controller.AssetTicketCoordinator.ITAsset("ID-123", assetLabel, "Standard model", "SN-9876");

        java.util.List<com.example.stockit.controller.AssetTicketCoordinator.Ticket> tickets = new java.util.ArrayList<>();
        tickets.add(new com.example.stockit.controller.AssetTicketCoordinator.Ticket("TK-001", "CEO monitor broken", "Monitor", "CRITICAL", System.currentTimeMillis()));
        tickets.add(new com.example.stockit.controller.AssetTicketCoordinator.Ticket("TK-002", "Thomas laptop is slow", "Computer", "HIGH", System.currentTimeMillis() + 3600000));
        tickets.add(new com.example.stockit.controller.AssetTicketCoordinator.Ticket("TK-003", "Sarah needs a mouse", "Mouse", "LOW", System.currentTimeMillis() + 86400000));

        com.example.stockit.model.User currentUser = com.example.stockit.controller.MainController.getCurrentUser();
        String techName = (currentUser != null) ? currentUser.getUsername() : "Nadhem";
        com.example.stockit.controller.AssetTicketCoordinator.Technician tech = 
            new com.example.stockit.controller.AssetTicketCoordinator.Technician(techName, "EMP-01");

        coordinator.coordinateAssetReceipt(asset, tickets, tech);
    }

    private void updateDashboardData() {
        applyHeroGreeting();
        renderHeroChart();
        refreshRecentAssets();
        refreshKanban();

        controller.getReportData((total, low, value, out, counts) -> {
            android.widget.TextView txtTotal = findViewById(R.id.dashTotalStock);
            android.widget.TextView txtValue = findViewById(R.id.dashTotalValue);
            android.widget.TextView txtAlerts = findViewById(R.id.dashAlertText);
            android.widget.TextView txtHeroSub = findViewById(R.id.dashHeroSubtitle);

            if (txtTotal != null) animateInt(txtTotal, total, null);
                if (txtValue != null) animateDouble(txtValue, value, " EUR");
                if (txtAlerts != null) txtAlerts.setText(out + " out of stock, " + low + " low");
                if (txtHeroSub != null) txtHeroSub.setText(
                    total + " items | " + out + " out of stock");
        });

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
            android.widget.TextView empty = findViewById(R.id.dashRecentMovementsEmpty);
            if (rv != null) {
                rv.setLayoutManager(new LinearLayoutManager(this));
                Collections.reverse(movements);
                List<com.example.stockit.model.StockMovement> limited = movements.size() > 3 ? movements.subList(0, 3) : movements;
                rv.setAdapter(new StockMovementAdapter(limited));
                if (empty != null) {
                    empty.setVisibility(limited.isEmpty() ? View.VISIBLE : View.GONE);
                }
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
        String displayName = com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(p.getName());
        ((android.widget.TextView)view.findViewById(R.id.detailName)).setText(displayName);
        ((android.widget.TextView)view.findViewById(R.id.detailStock)).setText(getString(R.string.txt_current_stock, p.getQuantity()));
        
        android.widget.TextView txtKit = view.findViewById(R.id.detailKit);
        if (p.getName().toLowerCase().contains("screen") || p.getName().toLowerCase().contains("monitor")) {
            txtKit.setText("Warning: KIT: HDMI Cable + Power Adapter");
            txtKit.setVisibility(View.VISIBLE);
        }

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this).setView(view).create();
        view.findViewById(R.id.btnDetailChrono).setOnClickListener(v -> { dialog.dismiss(); showProductTimeline(p); });
        view.findViewById(R.id.btnDetailZycus).setOnClickListener(v -> {
            dialog.dismiss();
            controller.createZycusOrder(p, 5, pr -> Toast.makeText(this, getString(R.string.toast_pr_created, pr), Toast.LENGTH_LONG).show());
        });
        view.findViewById(R.id.btnDetailAlert).setOnClickListener(v -> {
            dialog.dismiss();
            controller.triggerN8nAlert(p, () -> Toast.makeText(this, R.string.toast_alert_sent, Toast.LENGTH_SHORT).show());
        });
        dialog.show();
    }

    private void showProductTimeline(Product p) {
        controller.getProductMovements(p.getId(), movements -> {
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new com.example.stockit.controller.TimelineAdapter(movements));
            String displayName = com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(p.getName());
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(getString(R.string.dlg_title_timeline, displayName)).setView(rv).setPositiveButton(R.string.action_ok, null).show();
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
            String reason = inputReason != null ? inputReason.getText().toString().trim() : "Manual addition";
            
            if (name.isEmpty() || qtyS.isEmpty()) {
                Toast.makeText(this, R.string.toast_fill_name_qty, Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                int q = Integer.parseInt(qtyS);
                double p = Double.parseDouble(priceS);
                
                controller.addProduct(name, "IT", null, "Manual entry", "ASSET-" + System.currentTimeMillis(), q, p, "", "", reason, () -> {
                    Toast.makeText(this, getString(R.string.toast_article_added, name), Toast.LENGTH_SHORT).show();
                    if (inputName != null) inputName.setText("");
                    if (inputQty != null) inputQty.setText("");
                    if (inputPrice != null) inputPrice.setText("");
                    if (inputReason != null) inputReason.setText("");
                    refreshArticles();
                    updateDashboardData();
                });
            } catch (Exception e) {
                Toast.makeText(this, R.string.toast_number_format_error, Toast.LENGTH_SHORT).show();
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
        if (id == R.id.nav_article) { showLayout(layoutArticle); refreshArticles(); }
        else if (id == R.id.nav_analyses) { showLayout(layoutReports); refreshReports(); }
        else if (id == R.id.nav_ai) { showLayout(layoutAI); refreshAI(); }
        else if (id == R.id.nav_fournisseur) { showLayout(layoutSupplier); refreshSuppliers(); }
        else if (id == R.id.nav_commandes) { showLayout(layoutPurchase); refreshPurchases(); }
        else if (id == R.id.nav_utilisateur) { showLayout(layoutManageUser); refreshUsers(); }
        else if (id == R.id.nav_audit) { showLayout(layoutAudit); refreshAudit(); }
        else if (id == R.id.nav_alert_history) { startActivity(new Intent(this, AlertHistoryActivity.class)); }
        else if (id == R.id.nav_leaderboard) { showLayout(layoutLeaderboard); refreshLeaderboard(); }
        else if (id == R.id.nav_configuration) { showLayout(layoutSettings); setupSettingsControls(); }
        else if (id == R.id.nav_deconnexion) { performLogout(); }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    private void setupSettingsControls() {
        android.widget.Spinner spinnerLanguage = findViewById(R.id.spinnerLanguage);
        if (spinnerLanguage != null) {
            android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item,
                    com.example.stockit.util.LocaleHelper.SUPPORTED_LABELS);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerLanguage.setAdapter(adapter);
            spinnerLanguage.setSelection(
                    com.example.stockit.util.LocaleHelper.currentIndex(this),
                    false);

            spinnerLanguage.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    String currentTag = com.example.stockit.util.LocaleHelper.currentTag(MainActivity.this);
                    String newTag = com.example.stockit.util.LocaleHelper.SUPPORTED_TAGS[position];
                    if (currentTag.equals(newTag)) return;
                    com.example.stockit.util.LocaleHelper.apply(MainActivity.this, newTag);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.toast_language_changed,
                                    com.example.stockit.util.LocaleHelper.SUPPORTED_LABELS[position]),
                            Toast.LENGTH_SHORT).show();
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }

        com.google.android.material.switchmaterial.SwitchMaterial switchDark = findViewById(R.id.switchDarkMode);
        if (switchDark != null) {
            android.content.SharedPreferences prefs = getSharedPreferences("stockit_ui", MODE_PRIVATE);
            boolean savedDark = prefs.getBoolean("dark_mode", false);
            switchDark.setChecked(savedDark);
            switchDark.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("dark_mode", isChecked).apply();
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        isChecked
                                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                                : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
            });
        }
    }

    private void performLogout() {
        com.example.stockit.util.SessionManager.get(this).clear();
        final android.content.Context appCtx = getApplicationContext();
        final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable relaunchSplash = () -> mainHandler.post(() -> {
            Intent i = new Intent(appCtx, SplashActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            appCtx.startActivity(i);
        });
        logout(() -> {
            relaunchSplash.run();
            finish();
        });
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                android.util.Log.w("MainActivity", "logout timeout - force splash relaunch");
                relaunchSplash.run();
                finish();
            }
        }, 3000);
    }

    private void refreshReports() {
        View btnExport = findViewById(R.id.btnExportReport);
        if (btnExport != null) btnExport.setOnClickListener(v -> generateMonthlyPdfReport());
        View btnGenAi = findViewById(R.id.btnGenerateAiReport);
        if (btnGenAi != null) btnGenAi.setOnClickListener(v -> generateMonthlyPdfReport());

        controller.getReportData((total, low, value, out, counts) -> {
            android.widget.TextView tv = findViewById(R.id.reportTotalValue);
            if (tv != null) tv.setText(String.format(Locale.getDefault(), "%.2f EUR", value));

            android.widget.TextView tvOut = findViewById(R.id.reportOutOfStock);
            if (tvOut != null) tvOut.setText(out + " " + getString(R.string.rep_articles_short));

            android.widget.TextView tvItems = findViewById(R.id.reportTotalItems);
            if (tvItems != null) tvItems.setText(getString(R.string.rep_items_in_stock) + " " + total);

            android.widget.TextView tvLow = findViewById(R.id.reportLowStockCount);
            if (tvLow != null) tvLow.setText(getString(R.string.rep_low_stock_alerts) + " " + low);

            android.widget.TextView tvDetails = findViewById(R.id.reportCategoryDetails);
            if (tvDetails != null && counts != null) {
                StringBuilder sb = new StringBuilder();
                for (com.example.stockit.model.ProductDao.CategoryCount c : counts) {
                    sb.append("- ").append(c.category).append(" : ")
                            .append(c.total).append("\n");
                }
                tvDetails.setText(sb.toString().trim());
            }

            com.github.mikephil.charting.charts.PieChart pieChart = findViewById(R.id.pieChart);
            if (pieChart != null && counts != null && !counts.isEmpty()) {
                java.util.List<com.github.mikephil.charting.data.PieEntry> entries = new java.util.ArrayList<>();
                for (com.example.stockit.model.ProductDao.CategoryCount c : counts) {
                    entries.add(new com.github.mikephil.charting.data.PieEntry(c.total, c.category));
                }
                com.github.mikephil.charting.data.PieDataSet dataSet =
                        new com.github.mikephil.charting.data.PieDataSet(entries, getString(R.string.rep_categories));
                dataSet.setColors(com.github.mikephil.charting.utils.ColorTemplate.MATERIAL_COLORS);
                com.github.mikephil.charting.data.PieData data = new com.github.mikephil.charting.data.PieData(dataSet);
                pieChart.setData(data);
                pieChart.getDescription().setEnabled(false);
                pieChart.invalidate();
            }

            com.github.mikephil.charting.charts.BarChart barChart = findViewById(R.id.barChart);
            if (barChart != null && counts != null && !counts.isEmpty()) {
                java.util.List<com.github.mikephil.charting.data.BarEntry> bars = new java.util.ArrayList<>();
                java.util.List<String> labels = new java.util.ArrayList<>();
                for (int i = 0; i < counts.size(); i++) {
                    bars.add(new com.github.mikephil.charting.data.BarEntry(i, counts.get(i).total));
                    labels.add(counts.get(i).category);
                }
                com.github.mikephil.charting.data.BarDataSet barSet =
                        new com.github.mikephil.charting.data.BarDataSet(bars, getString(R.string.rep_qty_by_category));
                barSet.setColors(com.github.mikephil.charting.utils.ColorTemplate.MATERIAL_COLORS);
                barChart.setData(new com.github.mikephil.charting.data.BarData(barSet));
                barChart.getXAxis().setValueFormatter(
                        new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(labels));
                barChart.getXAxis().setPosition(
                        com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM);
                barChart.getXAxis().setGranularity(1f);
                barChart.getDescription().setEnabled(false);
                barChart.invalidate();
            }

            com.github.mikephil.charting.charts.RadarChart radarChart = findViewById(R.id.radarChart);
            if (radarChart != null && counts != null && !counts.isEmpty()) {
                java.util.List<com.github.mikephil.charting.data.RadarEntry> radarEntries = new java.util.ArrayList<>();
                java.util.List<String> radarLabels = new java.util.ArrayList<>();
                for (com.example.stockit.model.ProductDao.CategoryCount c : counts) {
                    radarEntries.add(new com.github.mikephil.charting.data.RadarEntry(c.total));
                    radarLabels.add(c.category);
                }
                com.github.mikephil.charting.data.RadarDataSet radarSet =
                        new com.github.mikephil.charting.data.RadarDataSet(radarEntries, getString(R.string.rep_categories));
                radarSet.setColor(android.graphics.Color.parseColor("#1F4E79"));
                radarSet.setFillColor(android.graphics.Color.parseColor("#1F4E79"));
                radarSet.setDrawFilled(true);
                radarSet.setLineWidth(2f);
                radarChart.setData(new com.github.mikephil.charting.data.RadarData(radarSet));
                radarChart.getXAxis().setValueFormatter(
                        new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(radarLabels));
                radarChart.getDescription().setEnabled(false);
                radarChart.invalidate();
            }
        });

        com.github.mikephil.charting.charts.LineChart lineChart = findViewById(R.id.lineChart);
        if (lineChart != null) {
            controller.getStockMovements(movements -> {
                java.util.List<com.github.mikephil.charting.data.Entry> entries = new java.util.ArrayList<>();
                int n = Math.min(7, movements.size());
                for (int i = 0; i < n; i++) {
                    com.example.stockit.model.StockMovement m = movements.get(movements.size() - n + i);
                    entries.add(new com.github.mikephil.charting.data.Entry(i, Math.abs(m.getQuantity())));
                }
                if (entries.isEmpty()) return;
                com.github.mikephil.charting.data.LineDataSet lineSet =
                        new com.github.mikephil.charting.data.LineDataSet(entries, getString(R.string.rep_last_movements));
                lineSet.setColor(android.graphics.Color.parseColor("#00AEEF"));
                lineSet.setCircleColor(android.graphics.Color.parseColor("#00AEEF"));
                lineSet.setLineWidth(2f);
                lineChart.setData(new com.github.mikephil.charting.data.LineData(lineSet));
                lineChart.getDescription().setEnabled(false);
                lineChart.invalidate();
            });
        }
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
                .setMessage(R.string.dlg_title_ai_report_loading).setCancelable(false).show();

        controller.getReportData((total, low, value, out, counts) -> {
            String rawData = String.format(Locale.getDefault(), 
                "Vistaprint inventory: %d items, %d out of stock, %d low stock. Value: %.2f EUR.",
                total, out, low, value);
            
            controller.generateAIReport(rawData, summary -> {
                loading.dismiss();
                File pdfFile = com.example.stockit.util.PdfReportGenerator.generateMonthlyReport(this, summary);
                if (pdfFile != null) {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("AI Report Completed")
                            .setMessage(R.string.dlg_msg_report_saved)
                            .setPositiveButton(R.string.action_share, (d, w) -> {
                                Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.example.stockit.fileprovider", pdfFile);
                                Intent intent = new Intent(Intent.ACTION_SEND);
                                intent.setType("application/pdf");
                                intent.putExtra(Intent.EXTRA_STREAM, uri);
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(Intent.createChooser(intent, "Share report"));
                            })
                            .setNegativeButton(R.string.action_close, null).show();
                } else {
                    Toast.makeText(this, R.string.toast_pdf_generation_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }


    private void populateDrawerHeader(NavigationView navigationView) {
        View header = navigationView.getHeaderView(0);
        if (header == null) return;

        com.example.stockit.util.SessionManager session =
                com.example.stockit.util.SessionManager.get(this);

        android.widget.TextView nameView  = header.findViewById(R.id.nav_user_name);
        android.widget.TextView emailView = header.findViewById(R.id.userEmail);
        android.widget.TextView roleView  = header.findViewById(R.id.userRole);

        String username = session.getUsername();
        String email    = session.getEmail();
        String role     = session.getRole();

        if (nameView != null && username != null && !username.isEmpty()) {
            String displayName = username;
            if (displayName.contains("@")) {
                displayName = displayName.substring(0, displayName.indexOf('@'));
            }
            String[] parts = displayName.replace('.', ' ').split("\\s+");
            StringBuilder pretty = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                if (pretty.length() > 0) pretty.append(' ');
                pretty.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) pretty.append(p.substring(1).toLowerCase());
            }
            nameView.setText(pretty.length() > 0 ? pretty.toString() : displayName);
        }
        if (emailView != null && email != null && !email.isEmpty()) {
            emailView.setText(email);
        }
        if (roleView != null && role != null && !role.isEmpty()) {
            roleView.setText(role);
        }
    }

    private void applyHeroGreeting() {
        android.widget.TextView greeting = findViewById(R.id.dashHeroGreeting);
        android.widget.TextView avatar   = findViewById(R.id.dashHeroAvatar);
        if (greeting == null && avatar == null) return;

        String userName = "User";
        View header = ((NavigationView) findViewById(R.id.nav_view)).getHeaderView(0);
        if (header != null) {
            android.widget.TextView navName = header.findViewById(R.id.nav_user_name);
            if (navName != null && navName.getText() != null && navName.getText().length() > 0) {
                String n = navName.getText().toString().trim();
                if (!n.isEmpty() && !"User Name".equalsIgnoreCase(n)) userName = n;
            }
        }

        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String hello = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

        if (greeting != null) greeting.setText(hello + " " + userName);
        if (avatar != null) {
            String initial = userName.substring(0, 1).toUpperCase(Locale.getDefault());
            avatar.setText(initial);
        }
    }

    private void refreshKanban() {
        android.widget.TextView createdV  = findViewById(R.id.kanbanCreatedValue);
        android.widget.TextView pendingV  = findViewById(R.id.kanbanPendingValue);
        android.widget.TextView assignedV = findViewById(R.id.kanbanAssignedValue);
        if (createdV == null || pendingV == null || assignedV == null) return;

        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        final long startOfDay = cal.getTimeInMillis();
        final String todayPrefix = new java.text.SimpleDateFormat(
                "dd/MM/yyyy", java.util.Locale.getDefault())
                .format(new java.util.Date(startOfDay));

        controller.getStockMovements(movements -> {
            int created = 0, assigned = 0;
            for (com.example.stockit.model.StockMovement m : movements) {
                if (m.getDate() == null || !m.getDate().startsWith(todayPrefix)) continue;
                if ("IN".equals(m.getType())) created += Math.abs(m.getQuantity());
                else if ("OUT".equals(m.getType())) assigned += Math.abs(m.getQuantity());
            }
            createdV.setText(String.valueOf(created));
            assignedV.setText(String.valueOf(assigned));
        });

        controller.getStock(products -> {
            int pending = 0;
            for (com.example.stockit.model.Product p : products) {
                if (p.getQuantity() > 0) pending += p.getQuantity();
            }
            pendingV.setText(String.valueOf(pending));
        });
    }

    private void refreshRecentAssets() {
        View header = findViewById(R.id.recentAssetsHeader);
        android.widget.LinearLayout container = findViewById(R.id.recentAssetsContainer);
        if (container == null) return;
        container.removeAllViews();

        java.util.List<com.example.stockit.util.RecentAssetsStore.Entry> items =
                com.example.stockit.util.RecentAssetsStore.load(this);
        if (header != null) header.setVisibility(View.VISIBLE);

        if (items.isEmpty()) {
            com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            card.setLayoutParams(lp);
            card.setRadius(16f);
            card.setCardElevation(2f);
            card.setUseCompatPadding(false);
            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(
                this, R.color.recent_card_bg));

            android.widget.TextView empty = new android.widget.TextView(this);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            empty.setPadding(pad, pad, pad, pad);
            empty.setText("No Jira assets created yet. Scan an item to create one.");
            empty.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.recent_card_text));
            empty.setAlpha(0.8f);
            card.addView(empty);
            container.addView(card);
            return;
        }

        int densityPad = (int) (12 * getResources().getDisplayMetrics().density);
        int marginBottom = (int) (8 * getResources().getDisplayMetrics().density);

        for (com.example.stockit.util.RecentAssetsStore.Entry e : items) {
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, marginBottom);
            card.setLayoutParams(lp);
            card.setRadius(16f);
            card.setCardElevation(2f);
            card.setUseCompatPadding(false);
            card.setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(
                    this, R.color.recent_card_bg));

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(densityPad, densityPad, densityPad, densityPad);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            android.widget.LinearLayout labels = new android.widget.LinearLayout(this);
            labels.setOrientation(android.widget.LinearLayout.VERTICAL);
            android.widget.LinearLayout.LayoutParams labelsLp = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labels.setLayoutParams(labelsLp);

            android.widget.TextView name = new android.widget.TextView(this);
            String displayAssetName = com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(e.name);
            name.setText(displayAssetName + " x" + e.quantity);
            name.setTextSize(14f);
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            name.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.recent_card_text));
            labels.addView(name);

            android.widget.TextView meta = new android.widget.TextView(this);
                meta.setText(e.jiraKey + " | "
                    + com.example.stockit.util.RecentAssetsStore.humanizeDelta(e.timestampMs));
            meta.setTextSize(12f);
            meta.setAlpha(0.7f);
            meta.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.recent_card_text));
            labels.addView(meta);

            row.addView(labels);

            android.widget.ImageButton btnJira = new android.widget.ImageButton(this);
            btnJira.setImageResource(android.R.drawable.ic_menu_view);
            btnJira.setBackgroundResource(android.R.color.transparent);
            btnJira.setContentDescription(getString(R.string.action_open_in_jira));
            btnJira.setAlpha(0.7f);
            android.widget.LinearLayout.LayoutParams btnLp = new android.widget.LinearLayout.LayoutParams(
                    (int) (40 * getResources().getDisplayMetrics().density),
                    (int) (40 * getResources().getDisplayMetrics().density));
            btnJira.setLayoutParams(btnLp);
            final String jiraKey = e.jiraKey;
            final String assetLabel = e.name;
            final int assetQty = e.quantity;
            btnJira.setOnClickListener(v -> {
                openAssetsLinksFlow(assetLabel);
            });
            row.addView(btnJira);

            android.widget.ImageButton btnSlack = new android.widget.ImageButton(this);
            btnSlack.setImageResource(android.R.drawable.ic_menu_share);
            btnSlack.setBackgroundResource(android.R.color.transparent);
            btnSlack.setContentDescription(getString(R.string.action_share_to_slack));
            btnSlack.setAlpha(0.7f);
            btnSlack.setLayoutParams(btnLp);
            btnSlack.setOnClickListener(v -> {
                String jiraUrlAll = com.example.stockit.util.JiraUrlHelper.assetsAllListUrl();
                String jiraUrlSpecific = com.example.stockit.util.JiraUrlHelper.assetsListUrlForName(assetLabel);
                String msg = ":package: [StockIT] " + assetLabel + " x" + assetQty
                    + " recorded -> " + jiraKey
                    + "\nGlobal Assets: " + jiraUrlAll
                    + "\nSpecific Assets: " + jiraUrlSpecific;
                com.example.stockit.util.SlackNotifier.send(msg);
                Toast.makeText(this, R.string.toast_shared_to_slack, Toast.LENGTH_SHORT).show();
            });
            row.addView(btnSlack);

            card.addView(row);
            card.setOnClickListener(v -> {
                openAssetsLinksFlow(assetLabel);
            });
            container.addView(card);
        }
    }

            private void openAssetsLinksFlow(String assetLabel) {
            String globalUrl = com.example.stockit.util.JiraUrlHelper.assetsAllListUrl();
            String specificUrl = com.example.stockit.util.JiraUrlHelper.assetsListUrlForName(assetLabel);

            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(globalUrl)));

            new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Jira Assets")
                .setMessage("1) Global list opened (typeId=905).\n\n"
                    + "2) Open specific list for this object: " + assetLabel + " ?")
                .setPositiveButton("Open specific", (d, w) ->
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(specificUrl))))
                .setNegativeButton("Keep global", null)
                .show();
            }

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

    private void renderHeroChart() {
        final com.github.mikephil.charting.charts.LineChart chart = findViewById(R.id.dashHeroChart);
        if (chart == null) return;

        controller.getStockMovements(movements -> {
            java.util.List<com.github.mikephil.charting.data.Entry> entries = new java.util.ArrayList<>();
            int n = Math.min(7, movements.size());
            if (n < 2) {
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
