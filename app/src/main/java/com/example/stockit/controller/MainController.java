package com.example.stockit.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.example.stockit.R;
import com.example.stockit.model.AppDatabase;
import com.example.stockit.model.AuditLog;
import com.example.stockit.model.Category;
import com.example.stockit.model.Product;
import com.example.stockit.model.PurchaseOrder;
import com.example.stockit.model.StockMovement;
import com.example.stockit.model.User;
import com.example.stockit.model.Claim;
import com.example.stockit.model.ApiService;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainController {
    private final Context context;
    private final AppDatabase db;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private static User currentUser;
    private final ApiService apiService;
    private final boolean hasClaimsBackend;

    /** Vrai si un backend réclamations réel est configuré (STOCKIT_API_BASE_URL). */
    public boolean hasClaimsBackend() { return hasClaimsBackend; }

    public interface StockCallback { void onStockLoaded(List<Product> products); }
    public interface ChatCallback { void onMessagesLoaded(List<com.example.stockit.model.ChatMessage> messages); }
    public interface AIResponseCallback { void onResponse(String text); }
    public interface AuthCallback { void onResult(boolean success, User user); }
    public interface CategoryCallback { void onCategoriesLoaded(List<Category> categories); }
    public interface UserCallback { void onUsersLoaded(List<User> users); }
    public interface MovementCallback { void onMovementsLoaded(List<StockMovement> movements); }
    public interface AuditCallback { void onAuditLoaded(List<AuditLog> logs); }
    public interface ReportCallback { void onReportLoaded(int total, int low, double value, int out, List<com.example.stockit.model.ProductDao.CategoryCount> categories); }
    public interface PurchaseCallback { void onOrdersLoaded(List<PurchaseOrder> orders); }
    public interface SupplierCallback { void onSuppliersLoaded(List<com.example.stockit.model.Supplier> suppliers); }
    public interface ShippingCallback { void onShippingOrdersLoaded(List<com.example.stockit.model.ShippingOrder> orders); }
    public interface AICallback { void onInsightsGenerated(List<com.example.stockit.model.AIInsight> insights); }
    public interface ClaimCallback { void onClaimsLoaded(List<Claim> claims); }
    public interface VisionCallback { void onLabelsDetected(List<String> labels, String fullText); }
    public interface QuestCallback { void onQuestsLoaded(List<com.example.stockit.model.Quest> quests); }

    public MainController(Context context) {
        this.context = context;
        android.util.Log.d("MainController", "Initializing MainController with context: " + context.getClass().getSimpleName());
        this.db = AppDatabase.getInstance(context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // Tentative de connexion au serveur local pour n8n/email
        // On augmente le timeout car un serveur local peut être lent à répondre
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                // Injecte automatiquement Authorization: Bearer <accessToken> Auth0
                // sur les appels au backend Vista (no-op si SSO non configuré ou
                // pas de session valide — cf. AuthBearerInterceptor).
                .addInterceptor(new com.example.stockit.util.AuthBearerInterceptor(context))
                .build();

        // Backend réclamations : STOCKIT_API_BASE_URL (identique iOS), sinon émulateur.
        String apiBase = "http://10.0.2.2/api/";
        try {
            String cfg = com.example.stockit.util.SharedBridge.apiBaseUrl();
            if (cfg != null && cfg.startsWith("http")) apiBase = cfg.endsWith("/") ? cfg : cfg + "/";
        } catch (Throwable ignored) { }
        this.hasClaimsBackend = !apiBase.contains("10.0.2.2");
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(apiBase)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        this.apiService = retrofit.create(ApiService.class);
    }

    /**
     * Assistant IA (AIChatActivity + rapport PDF).
     * 1) Claude via la passerelle Cimpress (même config que iOS), avec l'état réel du stock en contexte.
     * 2) Sinon réponse locale (OfflineAI) + cause lisible — plus aucun envoi direct
     *    à un fournisseur tiers hors Vista (Gemini retiré, TM-01 / TM-26).
     */
    public void askAssistant(String question, AIResponseCallback callback) {
        executor.execute(() -> {
            String context = buildStockContext();
            String system = context.isEmpty() ? str(R.string.ai_system_role)
                    : str(R.string.ai_system_role) + "\n\n" + context;

            // 1) Claude (Cimpress Gateway)
            com.example.stockit.util.CimpressChat.Result r =
                    com.example.stockit.util.CimpressChat.chat(system, question, 800);
            if (r.ok()) {
                String txt = com.example.stockit.util.CimpressChat.plainText(r.text);
                mainHandler.post(() -> callback.onResponse(txt));
                return;
            }
            android.util.Log.w("MainController", "Assistant Cimpress KO: " + r.error);

            // 2) Rien de disponible → cause lisible + réponse locale
            String why = "cimpress_not_configured".equals(r.error)
                    ? L("IA non configurée : renseignez CIMPRESS_GATEWAY_KEY et GATEWAY_URL dans .env.sh / set_env.ps1, puis recompilez.")
                    : L("Claude (Cimpress) indisponible : %@", r.error);
            String offline = offlineAnswer(question);
            mainHandler.post(() -> callback.onResponse(offline + "\n\n(" + why + ")"));
        });
    }

    private String str(int resId) { return context.getString(resId); }

    private static String L(String key, Object... args) {
        return com.example.stockit.util.AppLanguage.L(key, args);
    }

    /** Réponse locale à partir du stock réel quand l'IA est indisponible (identique iOS OfflineAI). */
    private String offlineAnswer(String question) {
        try {
            java.util.List<com.example.stockit.shared.model.Product> ps = new java.util.ArrayList<>();
            int total = 0, low = 0, out = 0; double value = 0;
            for (Product p : db.productDao().getAll()) {
                ps.add(com.example.stockit.util.SharedBridge.toShared(p));
                total += p.getQuantity(); value += p.getQuantity() * p.getUnitPrice();
                if (p.getQuantity() == 0) out++; else if (p.getQuantity() <= p.getMinThreshold()) low++;
            }
            com.example.stockit.shared.model.ReportData rd = new com.example.stockit.shared.model.ReportData(
                    total, low, value, out, new java.util.ArrayList<>());
            return com.example.stockit.shared.ai.OfflineAI.INSTANCE.answer(question, ps, rd, new java.util.ArrayList<>());
        } catch (Throwable t) {
            return L("Désolé, l'assistant IA ne répond pas.");
        }
    }

    /** Contexte stock envoyé au LLM pour répondre avec les vrais chiffres (même logique que iOS). */
    private String buildStockContext() {
        try {
            List<Product> products = db.productDao().getAll();
            List<StockMovement> moves = db.stockMovementDao().getAll();
            int total = 0, out = 0, low = 0;
            double value = 0;
            for (Product p : products) {
                total += p.getQuantity();
                value += p.getQuantity() * p.getUnitPrice();
                if (p.getQuantity() == 0) out++;
                else if (p.getQuantity() <= p.getMinThreshold()) low++;
            }
            StringBuilder sb = new StringBuilder("Données actuelles du stock IT (base StockIT) :\n");
            sb.append(String.format(java.util.Locale.FRANCE,
                    "- Total : %d articles, valeur %.2f €, %d en rupture, %d en stock bas.\n", total, value, out, low));
            sb.append("Produits (nom | catégorie | quantité | seuil | prix unitaire) :\n");
            for (int i = 0; i < Math.min(150, products.size()); i++) {
                Product p = products.get(i);
                sb.append(String.format(java.util.Locale.FRANCE, "- %s | %s | %d | %d | %.2f €\n",
                        p.getName(), p.getCategory() == null ? "-" : p.getCategory(),
                        p.getQuantity(), p.getMinThreshold(), p.getUnitPrice()));
            }
            if (!moves.isEmpty()) {
                sb.append("Derniers mouvements :\n");
                for (int i = 0; i < Math.min(15, moves.size()); i++) {
                    StockMovement m = moves.get(i);
                    sb.append("- ").append(m.getDate()).append(' ').append(m.getType()).append(' ')
                      .append(m.getQuantity()).append(" x ").append(m.getProductName())
                      .append(" (").append(m.getReason() == null ? "" : m.getReason()).append(")\n");
                }
            }
            sb.append("Réponds en français, de façon concise, en t'appuyant uniquement sur ces données. Pas de markdown.");
            return sb.toString();
        } catch (Exception e) {
            android.util.Log.w("MainController", "stock context failed", e);
            return "";
        }
    }

    /**
     * Analyse de facture (texte du prompt système iOS) via la passerelle Cimpress —
     * plus aucun appel direct à l'API Gemini (clé en query string, TM-26).
     */
    public void analyzeInvoice(android.net.Uri fileUri, AIResponseCallback callback) {
        executor.execute(() -> {
            com.example.stockit.util.CimpressChat.Result r =
                    com.example.stockit.util.CimpressChat.chat(
                            context.getString(R.string.ai_invoice_system_role),
                            context.getString(R.string.ai_invoice_user_prompt), 1200);
            if (r.ok()) {
                mainHandler.post(() -> callback.onResponse(com.example.stockit.util.CimpressChat.plainText(r.text)));
            } else {
                mainHandler.post(() -> callback.onResponse(context.getString(R.string.ai_error_analysis,
                        "Claude (Cimpress) indisponible : " + r.error)));
            }
        });
    }

    public void login(String username, String password, AuthCallback callback) {
        android.util.Log.d("MainController", "Login requested for: " + username);
        // TM-08 : le repli local (comptes à mot de passe) n'existe que dans les builds
        // Debug — en Release, seul le SSO Vista est disponible.
        if (!com.example.stockit.BuildConfig.DEBUG) {
            mainHandler.post(() -> callback.onResult(false, null));
            return;
        }
        final String name = username == null ? "" : username.trim();
        if (name.isEmpty() || password == null || password.isEmpty()) {
            mainHandler.post(() -> callback.onResult(false, null));
            return;
        }
        executor.execute(() -> {
            User user = null;
            if (com.example.stockit.util.LoginGuard.isLockedOut(name)) {
                recordAuditLog("LOGIN_LOCKED", "Connexion refusée (trop d'échecs) : " + name);
            } else {
                try {
                    User row = db.userDao().findLocalByUsername(name);
                    // Vérification PBKDF2 (jamais de comparaison en clair) hors du thread UI.
                    boolean ok = row != null && com.example.stockit.shared.security.PasswordHasher.INSTANCE
                            .verify(password, row.getPassword() == null ? "" : row.getPassword());
                    if (ok) {
                        com.example.stockit.util.LoginGuard.clear(name);
                        user = row;
                        recordAuditLog("LOGIN", "Utilisateur connecté: " + name);
                    } else {
                        com.example.stockit.util.LoginGuard.recordFailure(name);
                        recordAuditLog("LOGIN_FAILED", "Échec de connexion locale : " + name);
                    }
                } catch (Exception e) {
                    android.util.Log.e("MainController", "Login CRASH: " + e.getMessage(), e);
                }
            }

            final User finalUser = user;
            if (finalUser != null) currentUser = finalUser;
            mainHandler.post(() -> callback.onResult(finalUser != null, finalUser));
        });
    }

    /**
     * Crée (ou réinitialise) un compte local — développement / tests uniquement.
     * Mot de passe haché PBKDF2 ; refusé si le nom appartient déjà à un compte SSO.
     */
    public void provisionLocalUser(String username, String password, String role, AuthCallback callback) {
        if (!com.example.stockit.BuildConfig.DEBUG || username == null || username.trim().isEmpty() || password == null || password.length() < 10) {
            mainHandler.post(() -> callback.onResult(false, null));
            return;
        }
        final String name = username.trim();
        final String hash = com.example.stockit.shared.security.PasswordHasher.INSTANCE.hash(password, com.example.stockit.shared.security.PasswordHasher.DEFAULT_ITERATIONS);
        final String r = com.example.stockit.util.Permissions.normalize(role);
        executor.execute(() -> {
            try {
                User existing = db.userDao().findLocalByUsername(name);
                if (existing == null) {
                    User u = new User(name, hash, r);
                    db.userDao().register(u);
                } else {
                    existing.setPassword(hash);
                    existing.setRole(r);
                    db.userDao().update(existing);
                }
                recordAuditLog("PROVISION_LOCAL_USER", "Compte local " + name + " (" + r + ")");
                User saved = db.userDao().findLocalByUsername(name);
                mainHandler.post(() -> callback.onResult(true, saved));
            } catch (Exception e) {
                android.util.Log.e("MainController", "Provision CRASH: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onResult(false, null));
            }
        });
    }

    private void recordAuditLog(String action, String details) {
        executor.execute(() -> {
            String uId = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_guest);
            String device = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
            // TM-15 : pas d'adresse IP inventée (« 127.0.0.1 ») — le téléphone n'en
            // connaît pas la sienne côté client ; l'appareil est l'information fiable.
            AuditLog log = new AuditLog(uId, action, details, System.currentTimeMillis(), null, device);
            db.auditLogDao().insert(log);
        });
    }

    /**
     * Utilisateur authentifié via SSO Auth0 (Vista). L'identité est le sujet Auth0
     * (« sub », immuable) : un employé nommé « admin » ne récupère PAS un compte
     * local existant ; les comptes locaux ne sont jamais modifiés par une connexion
     * SSO (aucun mot de passe local). Une ancienne ligne SSO sans sujet est adoptée
     * une fois puis liée.
     */
    public void loginSso(String subject, String username, String role, AuthCallback callback) {
        final String name = username == null || username.trim().isEmpty() ? "vista.user" : username.trim();
        final String r = com.example.stockit.util.Permissions.normalize(role);
        android.util.Log.d("MainController", "SSO login: " + name + " (role=" + r + ")");
        executor.execute(() -> {
            try {
                java.util.List<User> all = db.userDao().getAllUsers();
                long matchId = com.example.stockit.util.SsoIdentity.resolve(toIdentityRows(all), subject, name);
                User user = null;
                if (matchId > 0) {
                    for (User u : all) if (u.getId() == matchId) { user = u; break; }
                }

                if (user != null) {
                    // Ligne SSO existante (ou ancienne ligne adoptée) : liaison du sujet + rôle à jour.
                    if (user.getAuthSubject() == null || user.getAuthSubject().isEmpty()) {
                        db.userDao().bindAuthSubject(subject, user.getId());
                        user.setAuthSubject(subject);
                    }
                    if (!r.equals(user.getRole())) {
                        user.setRole(r);
                        db.userDao().update(user);
                    }
                } else {
                    // Pas de ligne réutilisable : nouveau compte SSO (nom unique dérivé du sujet).
                    String unique = uniqueUsername(name, subject);
                    User u = new User(unique, "", r, subject, com.example.stockit.util.SsoIdentity.PROVIDER_AUTH0);
                    db.userDao().register(u);
                    user = db.userDao().findByAuthSubject(subject);
                    if (user == null) user = u;
                }

                currentUser = user;
                recordAuditLog("LOGIN_SSO", "Connexion SSO Auth0 : " + name + " (" + r + ")");

                final User finalSsoUser = user;
                mainHandler.post(() -> callback.onResult(true, finalSsoUser));
            } catch (Exception e) {
                android.util.Log.e("MainController", "SSO login CRASH: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onResult(false, null));
            }
        });
    }

    /** « Sam » sinon « Sam (a1b2c3) » d'après le sujet Auth0. */
    private String uniqueUsername(String name, String subject) {
        java.util.List<User> all = db.userDao().getAllUsers();
        boolean taken = false;
        for (User u : all) {
            if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(name)) { taken = true; break; }
        }
        if (!taken) return name;
        String suffix = subject == null ? "sso" : subject.replaceAll("[^A-Za-z0-9]", "");
        if (suffix.length() > 6) suffix = suffix.substring(suffix.length() - 6);
        String candidate = name + " (" + suffix + ")";
        int i = 2;
        while (true) {
            boolean exists = false;
            for (User u : all) {
                if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(candidate)) { exists = true; break; }
            }
            if (!exists) return candidate;
            candidate = name + " (" + suffix + "-" + i++ + ")";
        }
    }

    public static User getCurrentUser() { return currentUser; }

    /** Session ouverte par la biométrie (LoginActivity) : utilisateur déjà vérifié. */
    public static void setSessionUser(User user) { currentUser = user; }

    /**
     * Restaure l'utilisateur courant depuis la session de ce lancement (processus
     * relancé par le système). TM-09 : le rôle de la préférence n'est JAMAIS une
     * autorité — seule la ligne Room (mise à jour par SSO/admin) fait foi ; aucun
     * compte n'est créé ici.
     */
    public void restoreSession(String username, String role, Runnable onDone) {
        executor.execute(() -> {
            User found = null;
            if (username != null && !username.trim().isEmpty()) {
                for (User u : db.userDao().getAllUsers()) {
                    if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(username.trim())) { found = u; break; }
                }
            }
            if (found == null) {
                // Aucun compte connu : pas de session fantôme, retour au login.
                currentUser = null;
            } else {
                currentUser = found;
            }
            if (onDone != null) mainHandler.post(onDone);
        });
    }

    /** Ferme la session locale (équivalent iOS repo.logout()). Le mode test ne survit pas. */
    public static void logoutLocal() {
        currentUser = null;
        com.example.stockit.shared.config.StockItConfig.INSTANCE.setTestMode(false);
    }

    /** Message "Permission refusée" (même texte qu'iOS) affiché sur le thread principal. */
    private boolean denied(boolean adminOnly) {
        final String msg = adminOnly
                ? L("Permission refusée : rôle ADMIN requis")
                : L("Permission refusée : rôle ADMIN ou MANAGER requis");
        mainHandler.post(() -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show());
        return true;
    }
    public boolean isAdmin() { return currentUser != null && com.example.stockit.util.Permissions.canAdminister(currentUser.getRole()); }
    public boolean canEditStock() { return currentUser != null && com.example.stockit.util.Permissions.canEditStock(currentUser.getRole()); }
    public boolean canAdjustStock() { return currentUser != null && com.example.stockit.util.Permissions.canAdjustStock(currentUser.getRole()); }

    /**
     * Mode test (aucune écriture externe) : ADMIN uniquement, tracé dans l'audit,
     * désactivé à la déconnexion (cf. logoutLocal / SplashActivity).
     */
    public void setTestMode(boolean on, Runnable onComplete) {
        if (!isAdmin()) {
            denied(true);
            return;
        }
        executor.execute(() -> {
            com.example.stockit.util.SharedBridge.setTestMode(context, on);
            recordAuditLog(on ? "TEST_MODE_ON" : "TEST_MODE_OFF", "Mode test " + (on ? "activé" : "désactivé"));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    // --- CATEGORIES ---
    public void getCategories(CategoryCallback callback) {
        executor.execute(() -> {
            List<Category> categories = db.categoryDao().getAll();
            if (categories.isEmpty()) {
                db.categoryDao().insert(new Category(context.getString(R.string.cat_accessories), null, "#00B0FF"));
                db.categoryDao().insert(new Category(context.getString(R.string.cat_computer), null, "#01579B"));
                db.categoryDao().insert(new Category(context.getString(R.string.cat_desktop), null, "#0091EA"));
                db.categoryDao().insert(new Category(context.getString(R.string.cat_laptop), null, "#80D8FF"));
                db.categoryDao().insert(new Category(context.getString(R.string.cat_monitor), null, "#006196"));
                categories = db.categoryDao().getAll();
            }
            List<Category> finalCategories = categories;
            mainHandler.post(() -> callback.onCategoriesLoaded(finalCategories));
        });
    }

    public void addCategory(String name, Integer parentId, String color, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.categoryDao().insert(new Category(name, parentId, color));
            recordAuditLog("ADD_CATEGORY", context.getString(R.string.audit_cat_added, name));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateCategory(Category category, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.categoryDao().update(category);
            recordAuditLog("UPDATE_CATEGORY", context.getString(R.string.audit_cat_updated, category.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteCategory(Category category, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.categoryDao().delete(category);
            recordAuditLog("DELETE_CATEGORY", context.getString(R.string.audit_cat_deleted, category.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    // --- OTHER METHODS (EXISTING) ---
    public void getUsers(UserCallback callback) {
        executor.execute(() -> {
            List<User> users = db.userDao().getAllUsers();
            mainHandler.post(() -> callback.onUsersLoaded(users));
        });
    }

    public void deleteUser(User user, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        if (currentUser != null && currentUser.getUsername() != null
                && currentUser.getUsername().equalsIgnoreCase(user.getUsername())) {
            mainHandler.post(() -> android.widget.Toast.makeText(context, L("Impossible de supprimer votre propre compte"), android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        executor.execute(() -> {
            db.userDao().deleteUser(user);
            recordAuditLog("DELETE_USER", "Utilisateur supprimé : " + user.getUsername());
            mainHandler.post(onComplete);
        });
    }

    public void getStock(StockCallback callback) {
        executor.execute(() -> {
            List<Product> products = db.productDao().getAll();
            mainHandler.post(() -> callback.onStockLoaded(products));
        });
    }

    public void searchProducts(String query, StockCallback callback) {
        executor.execute(() -> {
            List<Product> products = db.productDao().searchProducts("%" + query + "%");
            mainHandler.post(() -> callback.onStockLoaded(products));
        });
    }

    public void addProduct(String name, String category, Integer categoryId, String description, String assetTag, int quantity, double unitPrice, String mfgDate, String expDate, String reason, Runnable onComplete) {
        addProduct(name, category, categoryId, description, assetTag, quantity, unitPrice, mfgDate, expDate, reason,
                null, null, null, onComplete);
    }

    /** Surcharge PFE : enregistre aussi le PO source de la facture + description Claude + fournisseur. */
    public void addProduct(String name, String category, Integer categoryId, String description, String assetTag,
                           int quantity, double unitPrice, String mfgDate, String expDate, String reason,
                           String poNumber, String poDescription, String receivedFrom,
                           Runnable onComplete) {
        addProduct(name, category, categoryId, description, assetTag, quantity, unitPrice, mfgDate, expDate, reason,
                poNumber, poDescription, receivedFrom, null, null, null, onComplete);
    }

    /** Surcharge PFE complète : ajoute aussi les données extraites de l'étiquette carton. */
    public void addProduct(String name, String category, Integer categoryId, String description, String assetTag,
                           int quantity, double unitPrice, String mfgDate, String expDate, String reason,
                           String poNumber, String poDescription, String receivedFrom,
                           String articleNumber, String brand, String packagePoNumber,
                           Runnable onComplete) {
        addProduct(name, category, categoryId, description, assetTag, quantity, unitPrice, mfgDate, expDate, reason,
                poNumber, poDescription, receivedFrom, articleNumber, brand, packagePoNumber, true, onComplete);
    }

    /**
     * syncAssets : true → les unités sont créées dans Jira Assets ici (saisie manuelle) ;
     * false → l'appelant s'en charge (scan d'équipement → assetsCheckIn avec n° de série). Identique iOS.
     */
    public void addProduct(String name, String category, Integer categoryId, String description, String assetTag,
                           int quantity, double unitPrice, String mfgDate, String expDate, String reason,
                           String poNumber, String poDescription, String receivedFrom,
                           String articleNumber, String brand, String packagePoNumber,
                           boolean syncAssets, Runnable onComplete) {
        if (!canEditStock() && denied(false)) return;
        executor.execute(() -> {
            Product p = new Product(name, category, description, assetTag, quantity, unitPrice, mfgDate, expDate);
            p.setCategoryId(categoryId);
            if (poNumber != null && !poNumber.isEmpty()) p.setPoNumber(poNumber);
            if (poDescription != null && !poDescription.isEmpty()) p.setPoDescription(poDescription);
            if (receivedFrom != null && !receivedFrom.isEmpty()) p.setReceivedFrom(receivedFrom);
            if (articleNumber != null && !articleNumber.isEmpty()) p.setArticleNumber(articleNumber);
            if (brand != null && !brand.isEmpty()) p.setBrand(brand);
            if (packagePoNumber != null && !packagePoNumber.isEmpty()) p.setPackagePoNumber(packagePoNumber);
            long newId = db.productDao().insert(p);
            String movReason = (reason != null && !reason.isEmpty()) ? reason : context.getString(R.string.mov_new_product);
            if (poNumber != null && !poNumber.isEmpty()) movReason += " [PO " + poNumber + "]";
            recordMovement((int)newId, name, "IN", quantity, movReason, null);
            recordAuditLog("ADD_PRODUCT", context.getString(R.string.audit_prod_added, name, quantity));
            addPoints(10, "Ajout produit");
            if (syncAssets && quantity > 0) com.example.stockit.util.AssetsSync.onQuantityChanged((int) newId, quantity, "CREATE");
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateQuantity(Product product, int delta, Runnable onComplete) {
        updateQuantity(product, delta, true, onComplete);
    }

    /** syncAssets : répercute l'écart réel dans Jira Assets (false si l'appelant s'en charge). */
    public void updateQuantity(Product product, int delta, boolean syncAssets, Runnable onComplete) {
        // Tout utilisateur connecté peut ajuster la quantité via les boutons +/−
        // (technicien en réception, admin en correction manuelle…). Les rôles
        // ADMIN/MANAGER restent seuls autorisés à créer/supprimer un produit,
        // voir addProduct / deleteProduct plus haut.
        android.util.Log.d("QtyDebug", "updateQuantity called: product=" + (product != null ? product.getName() : "null")
                + " delta=" + delta + " currentUser=" + (currentUser != null ? currentUser.getUsername() : "NULL"));
        if (currentUser == null) {
            android.util.Log.w("QtyDebug", "updateQuantity ABORTED: currentUser is null");
            mainHandler.post(() -> android.widget.Toast.makeText(context, L("Session expirée : reconnectez-vous"), android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        if (!com.example.stockit.util.Permissions.canAdjustStock(currentUser.getRole())) {
            denied(false);
            return;
        }
        executor.execute(() -> {
            Product fresh = db.productDao().getById(product.getId());
            if (fresh != null) product.setQuantity(fresh.getQuantity());
            int oldQty = product.getQuantity();
            int newQty = Math.max(0, product.getQuantity() + delta);
            android.util.Log.d("QtyDebug", "Updating DB: " + product.getName() + " " + product.getQuantity() + " -> " + newQty);
            product.setQuantity(newQty);
            db.productDao().update(product);
            String type = (delta > 0) ? "IN" : "OUT";
            recordMovement(product.getId(), product.getName(), type, Math.abs(delta), context.getString(R.string.mov_manual_adj), null);
            recordAuditLog("UPDATE_QUANTITY", context.getString(R.string.audit_qty_adjusted, product.getName(), delta));
            int applied = newQty - oldQty;
            if (syncAssets && applied != 0) com.example.stockit.util.AssetsSync.onQuantityChanged(
                    product.getId(), applied, applied > 0 ? "ADJUST_IN" : "ADJUST_OUT");
            android.util.Log.d("QtyDebug", "DB updated. Posting UI refresh callback=" + (onComplete != null));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public interface BatchCallback { void onDone(int created, int updated); }

    /** Scan en rafale (iOS addOrIncrementByCode) : +1 si le code existe, sinon création. */
    public void addOrIncrementByCodes(java.util.List<String> codes, BatchCallback cb) {
        if (!canAdjustStock() && denied(false)) return;
        executor.execute(() -> {
            int created = 0, updated = 0;
            for (String code : codes) {
                Product existing = null;
                for (Product p : db.productDao().getAll()) {
                    if (code.equalsIgnoreCase(p.getAssetTag()) || code.equalsIgnoreCase(p.getArticleNumber())) { existing = p; break; }
                }
                if (existing != null) {
                    existing.setQuantity(existing.getQuantity() + 1);
                    db.productDao().update(existing);
                    recordMovement(existing.getId(), existing.getName(), "IN", 1, "Batch Scan", null);
                    com.example.stockit.util.AssetsSync.onQuantityChanged(existing.getId(), 1, "ADJUST_IN");
                    updated++;
                } else {
                    Product p = new Product("Produit " + code, "Batch", "Batch Scan", code, 1, 0.0, "", "");
                    long id = db.productDao().insert(p);
                    recordMovement((int) id, p.getName(), "IN", 1, "Batch", null);
                    com.example.stockit.util.AssetsSync.onQuantityChanged((int) id, 1, "CREATE");
                    created++;
                }
            }
            recordAuditLog("BATCH_SCAN", created + " créé(s), " + updated + " incrémenté(s)");
            if (created > 0) addPoints(10 * created, "Batch scan");
            final int c = created, u = updated;
            mainHandler.post(() -> cb.onDone(c, u));
        });
    }

    /** Entrée sur un produit existant (scan d'un modèle déjà en stock, identique iOS). */
    public void incrementExisting(Product product, int qty, String reason, String poNumber, Runnable onComplete) {
        if (!canAdjustStock()) { denied(false); return; }
        executor.execute(() -> {
            Product fresh = db.productDao().getById(product.getId());
            if (fresh == null) fresh = product;
            fresh.setQuantity(fresh.getQuantity() + qty);
            db.productDao().update(fresh);
            String r = (reason != null ? reason : "Réception") + (poNumber != null ? " [PO " + poNumber + "]" : "");
            recordMovement(fresh.getId(), fresh.getName(), "IN", qty, r, null);
            recordAuditLog("UPDATE_QUANTITY", context.getString(R.string.audit_qty_adjusted, fresh.getName(), qty));
            addPoints(10, "Réception");
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    /** Trace la décision utilisateur après un scan (qualité IA, identique iOS). */
    public void recordVisionFeedback(String detected, String finalName, int confidence, boolean serialRead) {
        recordAuditLog(com.example.stockit.util.SharedBridge.visionAuditAction(),
                com.example.stockit.util.SharedBridge.visionAuditDetail(detected, finalName, confidence, serialRead));
    }

    /** Trace publique (Jira Assets, etc.). */
    public void audit(String action, String details) { recordAuditLog(action, details); }

    /** Export CSV de l'inventaire (iOS buildStockCsv). */
    public void buildStockCsv(AIResponseCallback cb) {
        executor.execute(() -> {
            StringBuilder sb = new StringBuilder("ID;Nom;Catégorie;Quantité;Seuil;Prix unitaire;Valeur;Asset tag;Marque;Référence;PO;Fournisseur\n");
            for (Product p : db.productDao().getAll()) {
                sb.append(p.getId()).append(';').append(com.example.stockit.util.Csv.cell(p.getName())).append(';').append(com.example.stockit.util.Csv.cell(p.getCategory())).append(';')
                  .append(p.getQuantity()).append(';').append(p.getMinThreshold()).append(';')
                  .append(String.format(java.util.Locale.FRANCE, "%.2f", p.getUnitPrice())).append(';')
                  .append(String.format(java.util.Locale.FRANCE, "%.2f", p.getUnitPrice() * p.getQuantity())).append(';')
                  .append(com.example.stockit.util.Csv.cell(p.getAssetTag())).append(';').append(com.example.stockit.util.Csv.cell(p.getBrand())).append(';')
                  .append(com.example.stockit.util.Csv.cell(p.getArticleNumber())).append(';').append(com.example.stockit.util.Csv.cell(p.getPoNumber())).append(';')
                  .append(com.example.stockit.util.Csv.cell(p.getReceivedFrom())).append('\n');
            }
            String out = sb.toString();
            mainHandler.post(() -> cb.onResponse(out));
        });
    }

    public void updateUserRole(User user, String role, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            user.setRole(role);
            db.userDao().update(user);
            recordAuditLog("UPDATE_USER", user.getUsername() + " → " + role);
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateProduct(Product product, Runnable onComplete) {
        if (!canEditStock() && denied(false)) return;
        executor.execute(() -> {
            Product old = db.productDao().getById(product.getId());
            db.productDao().update(product);
            com.example.stockit.util.AssetsSync.onProductEdited(old, product);
            recordAuditLog("UPDATE_PRODUCT", context.getString(R.string.audit_prod_updated, product.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteProduct(Product product, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            Product fresh = db.productDao().getById(product.getId());
            com.example.stockit.util.AssetsSync.onProductDeleted(fresh != null ? fresh : product);
            db.productDao().delete(product);
            recordMovement(product.getId(), product.getName(), "OUT", product.getQuantity(), context.getString(R.string.mov_deleted), null);
            recordAuditLog("DELETE_PRODUCT", context.getString(R.string.audit_prod_deleted, product.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void recordMovement(int productId, String productName, String type, int qty, String reason, String comment) {
        // Matrice de permissions : mouvements de stock réservés à ADMIN/MANAGER/TECHNICIEN.
        if (!canAdjustStock()) { denied(false); return; }
        if (qty <= 0 || qty > 1000 || (!"IN".equals(type) && !"OUT".equals(type))) return;
        executor.execute(() -> {
            String date = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String userName = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_system);
            db.stockMovementDao().insert(new StockMovement(productId, productName, type, qty, date, reason, userName, comment));
            Product prod = db.productDao().getById(productId);
            String tag = prod != null && prod.getAssetTag() != null && !prod.getAssetTag().isEmpty()
                    ? prod.getAssetTag() : "ID-" + productId;
            com.example.stockit.util.WebhookOutbox.notifyStockEvent(context, tag, productName, type, qty, reason, null);
        });
    }

    /**
     * StockIT PFE — Sortie de stock liée à un ticket Jira.
     * Décrémente Product.quantity et enregistre un StockMovement type=OUT avec le ticketId.
     * Bloque si stock insuffisant.
     */
    public void recordExitToTicket(final Product product, final int qty,
                                   final String ticketId, final String assignmentReason,
                                   final Runnable onSuccess, final Runnable onInsufficientStock) {
        if (product == null || qty <= 0) return;
        // Matrice : sorties sur ticket réservées à ADMIN/MANAGER/TECHNICIEN (TM-05).
        if (!canAdjustStock()) { denied(false); return; }
        if (qty > 1000) return;
        // Clé Jira validée avant tout mouvement / appel réseau (TM-42, TM-18).
        if (ticketId != null && !ticketId.isEmpty()
                && !"NO-TICKET".equalsIgnoreCase(ticketId)
                && !com.example.stockit.util.JiraPaths.isValidKey(ticketId)) return;
        executor.execute(() -> {
            // Recharger le produit à jour
            Product fresh = db.productDao().getById(product.getId());
            if (fresh == null) { if (onInsufficientStock != null) mainHandler.post(onInsufficientStock); return; }
            if (fresh.getQuantity() < qty) {
                if (onInsufficientStock != null) mainHandler.post(onInsufficientStock);
                return;
            }
            fresh.setQuantity(fresh.getQuantity() - qty);
            db.productDao().update(fresh);

            String date = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String userName = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_system);
            String reason = "Sortie sur ticket " + ticketId
                    + (assignmentReason != null && !assignmentReason.isEmpty() ? " — " + assignmentReason : "");
            StockMovement mv = new StockMovement(fresh.getId(), fresh.getName(), "OUT", qty, date, reason, userName, null);
            mv.setTicketId(ticketId);
            mv.setAssignmentReason(assignmentReason);
            db.stockMovementDao().insert(mv);
            String tag = fresh.getAssetTag() != null && !fresh.getAssetTag().isEmpty()
                    ? fresh.getAssetTag() : "ID-" + fresh.getId();
            com.example.stockit.util.WebhookOutbox.notifyStockEvent(context, tag, fresh.getName(), "OUT", qty, reason, ticketId);

            recordAuditLog("EXIT_TO_TICKET", fresh.getName() + " x" + qty + " → " + ticketId);
            addPoints(5, "Sortie ticket");
            if (onSuccess != null) mainHandler.post(onSuccess);
        });
    }

    // ================== StockIT PFE : cache d'analyse tickets ==================

    public interface AnalyzedTicketsCallback {
        void onLoaded(java.util.List<com.example.stockit.model.AnalyzedTicket> analyzed);
    }
    public interface FulfilledIdsCallback {
        void onLoaded(java.util.List<String> ticketIds);
    }

    /** Normalise un nom d'équipement pour la clé unique (lowercase + trim). */
    private static String equipmentKeyOf(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Persiste l'analyse LLM pour un couple (ticket, équipement).
     * Ne touche pas à `deliveredQty` (fait dans recordDeliveryToTicket).
     * NO-OP silencieux si ticketId vide.
     */
    public void saveLlmAnalysis(final String ticketId, final String equipmentName,
                                final String ticketSummary,
                                final int suggestedQty, final String reason) {
        if (ticketId == null || ticketId.isEmpty()) return;
        final String key = equipmentKeyOf(equipmentName);
        executor.execute(() -> {
            com.example.stockit.model.AnalyzedTicket existing =
                    db.analyzedTicketDao().getByTicketAndEquipment(ticketId, key);
            long now = System.currentTimeMillis();
            if (existing == null) {
                com.example.stockit.model.AnalyzedTicket t = new com.example.stockit.model.AnalyzedTicket();
                t.setTicketId(ticketId);
                t.setEquipmentKey(key);
                t.setEquipmentName(equipmentName);
                t.setTicketSummary(ticketSummary);
                t.setSuggestedQty(suggestedQty);
                t.setDeliveredQty(0);
                t.setReason(reason);
                t.setFulfilled(false);
                t.setAnalyzedAt(now);
                db.analyzedTicketDao().insert(t);
            } else {
                existing.setSuggestedQty(suggestedQty);
                existing.setReason(reason);
                existing.setAnalyzedAt(now);
                if (ticketSummary != null && !ticketSummary.isEmpty()) {
                    existing.setTicketSummary(ticketSummary);
                }
                db.analyzedTicketDao().update(existing);
            }
        });
    }

    /**
     * Incrémente `deliveredQty` de `qty` pour (ticketId, equipmentKey).
     * Si `deliveredQty >= suggestedQty` (et suggestedQty > 0), passe fulfilled=true.
     * Si aucune ligne n'existe (ex : mode manuel sans LLM), on en crée une
     * avec suggestedQty = deliveredQty → fulfilled immédiat.
     */
    public void recordDeliveryToTicket(final String ticketId, final String equipmentName,
                                       final int qty, final String reasonIfManual) {
        if (ticketId == null || ticketId.isEmpty() || qty <= 0) return;
        // "NO-TICKET" = sortie libre, on ne pollue pas le cache
        if ("NO-TICKET".equalsIgnoreCase(ticketId)) return;

        final String key = equipmentKeyOf(equipmentName);
        executor.execute(() -> {
            com.example.stockit.model.AnalyzedTicket t =
                    db.analyzedTicketDao().getByTicketAndEquipment(ticketId, key);
            long now = System.currentTimeMillis();
            if (t == null) {
                t = new com.example.stockit.model.AnalyzedTicket();
                t.setTicketId(ticketId);
                t.setEquipmentKey(key);
                t.setEquipmentName(equipmentName);
                t.setSuggestedQty(qty);
                t.setDeliveredQty(qty);
                t.setReason(reasonIfManual != null ? reasonIfManual : "Choix manuel");
                t.setAnalyzedAt(now);
                t.setFulfilled(true);
                t.setFulfilledAt(now);
                db.analyzedTicketDao().insert(t);
            } else {
                int delivered = t.getDeliveredQty() + qty;
                t.setDeliveredQty(delivered);
                if (t.getSuggestedQty() > 0 && delivered >= t.getSuggestedQty() && !t.isFulfilled()) {
                    t.setFulfilled(true);
                    t.setFulfilledAt(now);
                }
                db.analyzedTicketDao().update(t);
            }
        });
    }

    /**
     * Charge la liste des ticketIds entièrement livrés — à filtrer avant
     * de passer la liste Jira au LLM.
     */
    public void getFullyFulfilledTicketIds(final FulfilledIdsCallback cb) {
        executor.execute(() -> {
            java.util.List<String> ids = db.analyzedTicketDao().getFullyFulfilledTicketIds();
            mainHandler.post(() -> cb.onLoaded(ids != null ? ids : new java.util.ArrayList<>()));
        });
    }

    /** Toute la table `analyzed_tickets` (pour écran d'audit / debug). */
    public void getAnalyzedTickets(final AnalyzedTicketsCallback cb) {
        executor.execute(() -> {
            java.util.List<com.example.stockit.model.AnalyzedTicket> list =
                    db.analyzedTicketDao().getAll();
            mainHandler.post(() -> cb.onLoaded(list != null ? list : new java.util.ArrayList<>()));
        });
    }

    // ==========================================================================

    /** Adaptateur Room → vue minimale pour la décision de rattachement SSO. */
    private static java.util.List<com.example.stockit.util.SsoIdentity.Row> toIdentityRows(java.util.List<User> users) {
        java.util.List<com.example.stockit.util.SsoIdentity.Row> out = new java.util.ArrayList<>(users.size());
        for (User u : users) {
            final User f = u;
            out.add(new com.example.stockit.util.SsoIdentity.Row() {
                @Override public long getId() { return f.getId(); }
                @Override public String getUsername() { return f.getUsername(); }
                @Override public String getAuthSubject() { return f.getAuthSubject(); }
                @Override public String getProvider() {
                    return f.getProvider() == null || f.getProvider().isEmpty()
                            ? com.example.stockit.util.SsoIdentity.PROVIDER_LOCAL : f.getProvider();
                }
            });
        }
        return out;
    }

    public void getStockMovements(MovementCallback callback) {
        executor.execute(() -> {
            List<StockMovement> movements;
            if (isAdmin()) {
                movements = db.stockMovementDao().getAll();
            } else if (currentUser != null) {
                movements = db.stockMovementDao().getByUser(currentUser.getUsername());
            } else {
                movements = new java.util.ArrayList<>();
            }
            mainHandler.post(() -> callback.onMovementsLoaded(movements));
        });
    }

    public void getProductMovements(int productId, MovementCallback callback) {
        executor.execute(() -> {
            List<StockMovement> all = db.stockMovementDao().getAll();
            java.util.List<StockMovement> filtered = new java.util.ArrayList<>();
            for (StockMovement m : all) {
                if (m.getProductId() == productId) filtered.add(m);
            }
            mainHandler.post(() -> callback.onMovementsLoaded(filtered));
        });
    }

    public void getAuditLogs(AuditCallback callback) {
        executor.execute(() -> {
            List<AuditLog> logs = db.auditLogDao().getAll();
            mainHandler.post(() -> callback.onAuditLoaded(logs));
        });
    }

    public void getReportData(ReportCallback callback) {
        executor.execute(() -> {
            // Seed automatique si vide pour la démo
            if (db.productDao().getTotalQuantity() == 0) {
                seedStock(() -> {});
            }
            
            int total = db.productDao().getTotalQuantity();
            int low = db.productDao().getLowStockCount();
            double value = db.productDao().getTotalStockValue();
            int out = db.productDao().getOutOfStockCount();
            List<com.example.stockit.model.ProductDao.CategoryCount> categories = db.productDao().getCountByCategory();
            mainHandler.post(() -> callback.onReportLoaded(total, low, value, out, categories));
        });
    }

    public void seedStock(Runnable onComplete) {
        executor.execute(() -> {
            // Avec Jira Assets, le stock réel vient de la synchronisation : pas de données de démonstration (iOS).
            if (!com.example.stockit.util.AssetsSync.isConfigured() && db.productDao().getTotalQuantity() == 0) {
                db.productDao().insert(new Product("Laptop Dell Latitude", "Informatique", "Ordinateur portable", "DELL-LAT-01", 15, 1200.0, "10/01/2024", ""));
                db.productDao().insert(new Product("Souris Logitech MX", "Périphérique", "Souris ergonomique", "LOGI-MX-02", 4, 85.0, "15/01/2024", ""));
                db.productDao().insert(new Product("Ecran HP 24 pouces", "Informatique", "Moniteur Full HD", "HP-SCR-24", 2, 180.0, "20/01/2024", ""));
                
                db.stockMovementDao().insert(new StockMovement(1, "Laptop Dell Latitude", "IN", 15, "10/06/2024", "Réception", "admin", "Arrivée stock"));
                db.stockMovementDao().insert(new StockMovement(3, "Ecran HP 24 pouces", "OUT", 1, "12/06/2024", "Prêt", "Thomas", "Sortie pour bureau 302"));
            }
            mainHandler.post(onComplete);
        });
    }

    public void getPurchaseOrders(PurchaseCallback callback) {
        executor.execute(() -> {
            List<PurchaseOrder> orders = db.purchaseOrderDao().getAll();
            mainHandler.post(() -> callback.onOrdersLoaded(orders));
        });
    }

    public void createPurchaseOrder(String product, int qty, String supplier, Runnable onComplete) {
        if (!canEditStock() && denied(false)) return;
        executor.execute(() -> {
            String date = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(new java.util.Date());
            db.purchaseOrderDao().insert(new PurchaseOrder(product, qty, supplier, date, context.getString(R.string.purchase_status_pending)));
            recordAuditLog("CREATE_PURCHASE", context.getString(R.string.audit_purchase_created, product, qty));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updatePurchaseOrder(PurchaseOrder order, Runnable onComplete) {
        if (!canEditStock() && denied(false)) return;
        executor.execute(() -> {
            db.purchaseOrderDao().update(order);
            recordAuditLog("UPDATE_PURCHASE", "Commande mise à jour: " + order.getProductName());
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deletePurchaseOrder(PurchaseOrder order, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.purchaseOrderDao().delete(order);
            recordAuditLog("DELETE_PURCHASE", "Commande supprimée: " + order.getProductName());
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    // --- SUPPLIERS ---
    public void getSuppliers(SupplierCallback callback) {
        executor.execute(() -> {
            List<com.example.stockit.model.Supplier> suppliers = db.supplierDao().getAll();
            if (suppliers.isEmpty()) {
                db.supplierDao().insert(new com.example.stockit.model.Supplier("Dell France", "contact@dell.fr", "+33 1 00 00 00 00", "Montpellier", 5, 10.0));
                db.supplierDao().insert(new com.example.stockit.model.Supplier("Apple Store", "business@apple.com", "+33 1 11 11 11 11", "Paris", 3, 5.0));
                db.supplierDao().insert(new com.example.stockit.model.Supplier("Logitech Pro", "sales@logitech.com", "+41 21 000 00 00", "Lausanne", 7, 15.0));
                suppliers = db.supplierDao().getAll();
            }
            List<com.example.stockit.model.Supplier> finalSuppliers = suppliers;
            mainHandler.post(() -> callback.onSuppliersLoaded(finalSuppliers));
        });
    }

    public void addSupplier(String name, String email, String phone, String address, int leadTime, double discount, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.supplierDao().insert(new com.example.stockit.model.Supplier(name, email, phone, address, leadTime, discount));
            recordAuditLog("ADD_SUPPLIER", context.getString(R.string.audit_sup_added, name));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateSupplier(com.example.stockit.model.Supplier supplier, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.supplierDao().update(supplier);
            recordAuditLog("UPDATE_SUPPLIER", context.getString(R.string.audit_sup_updated, supplier.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteSupplier(com.example.stockit.model.Supplier supplier, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.supplierDao().delete(supplier);
            recordAuditLog("DELETE_SUPPLIER", context.getString(R.string.audit_sup_deleted, supplier.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    // --- SHIPPING ORDERS ---
    public void getShippingOrders(ShippingCallback callback) {
        executor.execute(() -> {
            List<com.example.stockit.model.ShippingOrder> orders = db.shippingOrderDao().getAll();
            if (orders.isEmpty()) {
                db.shippingOrderDao().insert(new com.example.stockit.model.ShippingOrder("Client Vistaprint", "Laptop Dell x2", "Paris", "TRACK123", "PENDING"));
                orders = db.shippingOrderDao().getAll();
            }
            List<com.example.stockit.model.ShippingOrder> finalOrders = orders;
            mainHandler.post(() -> callback.onShippingOrdersLoaded(finalOrders));
        });
    }

    public void addShippingOrder(String customer, String items, String address, String ticket, Runnable onComplete) {
        // Matrice : expéditions réservées à ADMIN/MANAGER/TECHNICIEN (TM-05).
        if (!canAdjustStock()) { denied(false); return; }
        if (customer == null || customer.trim().isEmpty()) return;
        executor.execute(() -> {
            com.example.stockit.model.ShippingOrder order = new com.example.stockit.model.ShippingOrder(customer, items, address, "", context.getString(R.string.ship_status_pending));
            order.setTicketNumber(ticket);
            db.shippingOrderDao().insert(order);
            recordAuditLog("ADD_SHIPPING", context.getString(R.string.audit_ship_created, customer));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateShippingOrder(com.example.stockit.model.ShippingOrder order, Runnable onComplete) {
        if (!canAdjustStock()) { denied(false); return; }
        executor.execute(() -> {
            db.shippingOrderDao().update(order);
            recordAuditLog("UPDATE_SHIPPING", context.getString(R.string.audit_ship_updated, String.valueOf(order.getId())));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteShippingOrder(com.example.stockit.model.ShippingOrder order, Runnable onComplete) {
        if (!isAdmin() && denied(true)) return;
        executor.execute(() -> {
            db.shippingOrderDao().delete(order);
            recordAuditLog("DELETE_SHIPPING", context.getString(R.string.audit_ship_deleted, String.valueOf(order.getId())));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    // --- AI ANALYSIS ---
    public void getAIInsights(AICallback callback) {
        generateAIInsights(callback);
    }

    public void generateAIInsights(AICallback callback) {
        executor.execute(() -> {
            List<Product> products = db.productDao().getAll();
            List<StockMovement> movements = db.stockMovementDao().getAll();
            List<com.example.stockit.model.AIInsight> insights = new java.util.ArrayList<>();

            for (Product p : products) {
                // 1. Prediction & Recommendation
                int monthlyOut = 0;
                for (StockMovement m : movements) {
                    if (m.getProductId() == p.getId() && "OUT".equals(m.getType())) {
                        monthlyOut += m.getQuantity();
                    }
                }
                
                if (p.getQuantity() < monthlyOut) {
                    insights.add(new com.example.stockit.model.AIInsight(
                        context.getString(R.string.ai_insight_recommendation, p.getName()),
                        context.getString(R.string.ai_insight_recommendation_desc, monthlyOut),
                        com.example.stockit.model.AIInsight.Type.RECOMMENDATION, "HIGH"
                    ));
                }

                // 2. Anomalies
                for (StockMovement m : movements) {
                    if (m.getProductId() == p.getId() && m.getQuantity() > 20) {
                        insights.add(new com.example.stockit.model.AIInsight(
                            context.getString(R.string.ai_insight_anomaly),
                            context.getString(R.string.ai_insight_anomaly_desc, m.getQuantity(), p.getName()),
                            com.example.stockit.model.AIInsight.Type.ANOMALY, "MEDIUM"
                        ));
                    }
                }

                // 3. Price Sugggestion (Dead stock)
                boolean hasRecentMovement = false;
                for (StockMovement m : movements) {
                    if (m.getProductId() == p.getId()) {
                        hasRecentMovement = true;
                        break;
                    }
                }
                if (!hasRecentMovement && p.getQuantity() > 0) {
                    insights.add(new com.example.stockit.model.AIInsight(
                        context.getString(R.string.ai_insight_dead_stock, p.getName()),
                        context.getString(R.string.ai_insight_dead_stock_desc),
                        com.example.stockit.model.AIInsight.Type.PRICE_SUGGESTION, "LOW"
                    ));
                }

                // 4. Risks (Expiration)
                if (p.getExpirationDate() != null && !p.getExpirationDate().isEmpty()) {
                    insights.add(new com.example.stockit.model.AIInsight(
                        context.getString(R.string.ai_insight_risk),
                        context.getString(R.string.ai_insight_risk_desc, p.getName(), p.getExpirationDate()),
                        com.example.stockit.model.AIInsight.Type.RISK, "HIGH"
                    ));
                }
            }

            if (insights.isEmpty()) {
                insights.add(new com.example.stockit.model.AIInsight(context.getString(R.string.ai_insight_done), context.getString(R.string.ai_insight_no_alerts), com.example.stockit.model.AIInsight.Type.PREDICTION, "LOW"));
            }

            List<com.example.stockit.model.AIInsight> finalInsights = insights;
            mainHandler.post(() -> callback.onInsightsGenerated(finalInsights));
        });
    }

    // --- RECLAMATIONS (MYSQL SYNC) ---
    public void getClaims(ClaimCallback callback) {
        executor.execute(() -> {
            // Charger les données : Admin voit tout, Employé voit seulement les siennes
            List<Claim> claims;
            if (isAdmin()) {
                claims = db.claimDao().getAll();
            } else if (currentUser != null) {
                claims = db.claimDao().getByUsername(currentUser.getUsername());
            } else {
                claims = new java.util.ArrayList<>();
            }
            
            List<Claim> finalClaims = claims;
            mainHandler.post(() -> callback.onClaimsLoaded(finalClaims));

            // Tenter une synchronisation avec MySQL
            if (!hasClaimsBackend) return;
            try {
                // On pourrait aussi filtrer côté serveur si l'API le supporte
                retrofit2.Response<List<Claim>> response = apiService.getClaims().execute();
                if (response.isSuccessful() && response.body() != null) {
                    // Logique de merge/update locale simplifiée
                    for (Claim c : response.body()) {
                        db.claimDao().insert(c); // Room ignorera si conflit selon config
                    }
                    // Re-notifier avec les nouvelles données filtrées
                    List<Claim> updated;
                    if (isAdmin()) updated = db.claimDao().getAll();
                    else if (currentUser != null) updated = db.claimDao().getByUsername(currentUser.getUsername());
                    else updated = new java.util.ArrayList<>();
                    
                    mainHandler.post(() -> callback.onClaimsLoaded(updated));
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Sync Claims Error: " + e.getMessage());
            }
        });
    }

    public void addClaim(String subject, String description, String priority, Runnable onComplete) {
        executor.execute(() -> {
            String sender = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.claims_anonymous);
            Claim newClaim = new Claim(subject, description, sender, priority);
            
            // 1. Sauvegarde locale (Room)
            db.claimDao().insert(newClaim);
            if (onComplete != null) mainHandler.post(onComplete);

            // 2. Envoi vers MySQL (Retrofit)
            if (!hasClaimsBackend) return;
            try {
                apiService.addClaim(newClaim).execute();
            } catch (Exception e) {
                android.util.Log.e("MainController", "Upload Claim Error: " + e.getMessage());
            }
        });
    }

    public void getMessages(int claimId, ChatCallback callback) {
        if (!hasClaimsBackend) {
            mainHandler.post(() -> android.widget.Toast.makeText(context,
                    L("Discussion indisponible : backend réclamations non configuré (STOCKIT_API_BASE_URL)"),
                    android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        executor.execute(() -> {
            try {
                retrofit2.Response<List<com.example.stockit.model.ChatMessage>> response = apiService.getMessages(claimId).execute();
                if (response.isSuccessful() && response.body() != null) {
                    mainHandler.post(() -> callback.onMessagesLoaded(response.body()));
                } else {
                    mainHandler.post(() -> android.widget.Toast.makeText(context, L("Erreur serveur : HTTP %@", response.code()), android.widget.Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Get Messages Error: " + e.getMessage());
                mainHandler.post(() -> android.widget.Toast.makeText(context, L("Serveur injoignable : %@", e.getMessage()), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    public void addMessage(int claimId, String text, Runnable onComplete) {
        executor.execute(() -> {
            String uId = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_guest);
            com.example.stockit.model.ChatMessage msg = new com.example.stockit.model.ChatMessage(uId, uId, text, System.currentTimeMillis());
            if (!hasClaimsBackend) {
                mainHandler.post(() -> android.widget.Toast.makeText(context,
                        L("Envoi impossible : backend réclamations non configuré"), android.widget.Toast.LENGTH_LONG).show());
                return;
            }
            try {
                retrofit2.Response<com.example.stockit.model.ChatMessage> r = apiService.addMessage(claimId, msg).execute();
                if (r.isSuccessful()) {
                    if (onComplete != null) mainHandler.post(onComplete);
                } else {
                    mainHandler.post(() -> android.widget.Toast.makeText(context, L("Erreur serveur : HTTP %@", r.code()), android.widget.Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Add Message Error: " + e.getMessage());
                mainHandler.post(() -> android.widget.Toast.makeText(context, L("Serveur injoignable : %@", e.getMessage()), android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    public interface ZycusCallback { void onPRCreated(String prNumber); }

    /**
     * Validation d'un brouillon de commande (iOS approveOrderDraft) : crée un bon de
     * commande local « En attente », trace l'audit et notifie Slack. Retourne "PO#id".
     */
    public void approveOrderDraft(String productName, int qty, String supplier, String justification,
                                  boolean byAI, ZycusCallback callback) {
        if (!canEditStock() && denied(false)) return;
        if (qty <= 0) {
            mainHandler.post(() -> android.widget.Toast.makeText(context, L("Quantité invalide"), android.widget.Toast.LENGTH_SHORT).show());
            return;
        }
        executor.execute(() -> {
            String date = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(new java.util.Date());
            db.purchaseOrderDao().insert(new PurchaseOrder(productName, qty, supplier, date, context.getString(R.string.purchase_status_pending)));
            List<PurchaseOrder> all = db.purchaseOrderDao().getAll();
            int id = 0;
            for (PurchaseOrder o : all) id = Math.max(id, o.getId());
            String ref = "PO#" + id;
            recordAuditLog("AI_ORDER_APPROVED", productName + " x" + qty + " — " + (supplier != null ? supplier : "fournisseur ?")
                    + " — " + ref + (byAI ? " [justification IA]" : ""));
            com.example.stockit.util.SlackNotifier.send(":shopping_trolley: [StockIT] Commande validée : " + productName + " x" + qty
                    + " (" + (supplier != null ? supplier : "fournisseur à définir") + ") — " + ref
                    + (justification != null ? "\n> " + (justification.length() > 200 ? justification.substring(0, 200) : justification) : ""));
            mainHandler.post(() -> callback.onPRCreated(ref));
        });
    }

    public void generateAIReport(String rawData, AIResponseCallback callback) {
        String prompt = "Agis comme un gestionnaire de stock IT expert pour Vistaprint. Voici les données brutes du mois : " + rawData + 
                       ". Rédige un rapport de synthèse professionnel en français (max 150 mots). Analyse les tendances, signale les alertes critiques et donne des recommandations. Utilise un ton pro mais concis.";
        askAssistant(prompt, callback);
    }

    /**
     * Vision (photo → nom de l'objet) via la passerelle Cimpress (Claude vision),
     * identique à l'app iOS — plus d'API Gemini directe ni de clé en query string
     * (TM-01, TM-26). L'image est lue et envoyée en base64 au gateway Vista.
     */
    public void detectObjectsGemini(android.net.Uri fileUri, VisionCallback callback) {
        executor.execute(() -> {
            try {
                java.io.InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
                java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = inputStream.read(buffer)) != -1) byteBuffer.write(buffer, 0, len);
                byte[] bytes = byteBuffer.toByteArray();
                inputStream.close();

                com.example.stockit.util.SharedBridge.identify(bytes, (name, confidence, serial, error) -> {
                    String label = name != null ? name : "Objet IT";
                    String detail = error != null
                            ? L("IA indisponible : %@", error)
                            : (serial != null && !serial.isEmpty() ? label + " (S/N " + serial + ")" : label);
                    mainHandler.post(() -> callback.onLabelsDetected(
                            new java.util.ArrayList<>(java.util.Collections.singletonList(label)), detail));
                });
            } catch (Exception e) {
                android.util.Log.w("MainController", "Vision error", e);
                mainHandler.post(() -> callback.onLabelsDetected(new java.util.ArrayList<>(), "Erreur"));
            }
        });
    }

    public void triggerN8nAlert(Product p, Runnable onComplete) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("event", "LOW_STOCK_ALERT");
        data.put("productName", p.getName());
        data.put("currentQty", p.getQuantity());
        data.put("threshold", p.getMinThreshold());
        data.put("user", currentUser != null ? currentUser.getUsername() : context.getString(R.string.user_name_system));
        data.put("timestamp", System.currentTimeMillis());
        data.put("email_to", com.example.stockit.BuildConfig.STOCKIT_DEFAULT_EMAIL);

        if (hasClaimsBackend) {
            apiService.sendAlert(data).enqueue(new retrofit2.Callback<Void>() {
                @Override public void onResponse(retrofit2.Call<Void> call, retrofit2.Response<Void> response) {
                    android.util.Log.d("MainController", "Server Alert Success Code: " + response.code());
                }
                @Override public void onFailure(retrofit2.Call<Void> call, Throwable t) {
                    android.util.Log.e("MainController", "Server Alert Connection Error: " + t.getMessage());
                }
            });
        }
        if (onComplete != null) mainHandler.post(onComplete);

        com.example.stockit.util.SlackNotifier.send("🚨 *ALERTE STOCK BAS* 🚨\nProduit: " + p.getName() + "\nQuantité actuelle: " + p.getQuantity());
        sendJiraTicket(p.getName(), p.getQuantity());

        // Email via workflow n8n — destinataire dédié "manager stock" (routé
        // côté Java, aucun changement n8n requis, voir StockItReporter overload).
        com.example.stockit.util.StockItReporter.sendEvent(context,
                "Alerte stock bas : " + p.getName(),
                "🚨 Le produit \"" + p.getName() + "\" est passé sous le seuil critique.\n\n"
                        + "• Quantité restante : " + p.getQuantity() + "\n"
                        + "• Seuil configuré  : " + p.getMinThreshold() + "\n\n"
                        + "👉 Merci de déclencher une commande fournisseur avant rupture.",
                com.example.stockit.BuildConfig.STOCKIT_DEFAULT_EMAIL);
    }

    // --- GAMIFICATION ---
    public void addPoints(int points, String reason) {
        if (currentUser == null) return;
        executor.execute(() -> {
            currentUser.setPoints(currentUser.getPoints() + points);
            // Niveau = points / 100
            int newLevel = (currentUser.getPoints() / 100) + 1;
            if (newLevel > currentUser.getLevel()) {
                currentUser.setLevel(newLevel);
                recordAuditLog("LEVEL_UP", "Niveau atteint: " + newLevel);
            }
            db.userDao().update(currentUser); // Update user in DB
            
            // Vérifier les quêtes
            List<com.example.stockit.model.Quest> quests = db.questDao().getActiveQuests();
            for (com.example.stockit.model.Quest q : quests) {
                q.setCurrentCount(q.getCurrentCount() + 1);
                if (q.getCurrentCount() >= q.getGoalCount()) {
                    q.setCompleted(true);
                    currentUser.setPoints(currentUser.getPoints() + q.getPointReward());
                    if (q.getBadgeReward() != null && !q.getBadgeReward().isEmpty()) {
                        String currentBadges = currentUser.getBadges();
                        currentUser.setBadges(currentBadges.isEmpty() ? q.getBadgeReward() : currentBadges + "," + q.getBadgeReward());
                    }
                    db.questDao().update(q);
                    db.userDao().update(currentUser);
                    recordAuditLog("QUEST_DONE", "Quête terminée: " + q.getTitle());
                } else {
                    db.questDao().update(q);
                }
            }
        });
    }

    public void getQuests(QuestCallback callback) {
        executor.execute(() -> {
            if (db.questDao().getQuestCount() == 0) {
                db.questDao().insert(new com.example.stockit.model.Quest("Pionnier du Stock", "Ajoutez 5 produits au stock", 5, 50, "Badge Bronze"));
                db.questDao().insert(new com.example.stockit.model.Quest("Inspecteur Expert", "Scannez 10 objets avec l'IA", 10, 100, "Badge Argent"));
            }
            List<com.example.stockit.model.Quest> quests = db.questDao().getActiveQuests();
            mainHandler.post(() -> callback.onQuestsLoaded(quests));
        });
    }

    public void getLeaderboard(UserCallback callback) {
        executor.execute(() -> {
            List<com.example.stockit.model.User> topUsers = db.userDao().getAllUsers();
            // Tri par points décroissants
            topUsers.sort((u1, u2) -> Integer.compare(u2.getPoints(), u1.getPoints()));
            mainHandler.post(() -> callback.onUsersLoaded(topUsers));
        });
    }

    public void sendSlackNotification(String message) {
        com.example.stockit.util.SlackNotifier.send(message);
    }

    public void sendJiraTicket(String productName, int qty) {
        String token = com.example.stockit.BuildConfig.JIRA_API_TOKEN;
        if (token == null || token.isEmpty() || token.equals("YOUR_JIRA_TOKEN_HERE")) {
            android.util.Log.w("MainController", "Jira Token missing. Skipping ticket creation.");
            return;
        }

        // On délègue au client centralisé (util/JiraClient) qui envoie déjà :
        //   - endpoint /rest/api/3/issue (ADF description),
        //   - issuetype = BuildConfig.JIRA_ISSUE_TYPE (ex: "Hardware request"),
        //   - components + customfield_11485 (Cost Center) requis par le projet SD.
        // L'ancienne implémentation locale envoyait "Task" sans customfields ni
        // component, ce que Jira Service Desk refuse avec un HTTP 400.
        String projectKey  = com.example.stockit.BuildConfig.JIRA_PROJECT_KEY;
        String summary     = "[StockIT] Alerte Stock: " + productName;
        String description = "Alerte automatique : la quantité de " + productName
                + " est descendue à " + qty + ".";

        com.example.stockit.util.JiraClient.createTask(
                projectKey,
                summary,
                description,
                (success, keyOrError) -> mainHandler.post(() -> {
                    if (success) {
                        android.widget.Toast.makeText(
                                context,
                                L("✅ Ticket Jira créé : %@", keyOrError),
                                android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        android.util.Log.e("MainController", "Jira create failed: " + keyOrError);
                        android.widget.Toast.makeText(
                                context,
                                L("❌ Erreur Jira : %@", keyOrError),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                }));
    }
}
