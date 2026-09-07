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

import com.google.ai.client.generativeai.GenerativeModel;
import com.google.ai.client.generativeai.java.GenerativeModelFutures;
import com.google.ai.client.generativeai.type.Content;
import com.google.ai.client.generativeai.type.GenerateContentResponse;

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
    private final com.example.stockit.model.AIService aiService;

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

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://10.0.2.2/api/") // Fallback Emulator
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        this.apiService = retrofit.create(ApiService.class);

        // Configuration Google Gemini 1.5 Pro
        Retrofit aiRetrofit = new Retrofit.Builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/") 
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        this.aiService = aiRetrofit.create(com.example.stockit.model.AIService.class);
    }

    public void askAssistant(String question, AIResponseCallback callback) {
        GenerativeModel gm = new GenerativeModel("gemini-1.5-pro", com.example.stockit.BuildConfig.GEMINI_API_KEY);
        GenerativeModelFutures model = GenerativeModelFutures.from(gm);

        Content content = new Content.Builder()
                .addText(context.getString(R.string.ai_system_role) + "\n\nQuestion: " + question)
                .build();

        com.google.common.util.concurrent.ListenableFuture<GenerateContentResponse> response = model.generateContent(content);
        
        response.addListener(() -> {
            try {
                GenerateContentResponse result = response.get();
                String resultText = result.getText();
                mainHandler.post(() -> callback.onResponse(resultText));
            } catch (Exception e) {
                android.util.Log.e("MainController", "Gemini SDK Error", e);
                mainHandler.post(() -> callback.onResponse("Désolé, une erreur est survenue avec l'IA."));
            }
        }, executor);
    }

    public void analyzeInvoice(android.net.Uri fileUri, AIResponseCallback callback) {
        executor.execute(() -> {
            try {
                if (fileUri != null) {
                    android.util.Log.d("MainController", "Analyzing invoice: " + fileUri.getPath());
                }
                // Pour l'analyse de facture, on demande à l'IA de renvoyer un JSON structuré
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                java.util.List<java.util.Map<String, Object>> messages = new java.util.ArrayList<>();
                
                java.util.Map<String, Object> systemMsg = new java.util.HashMap<>();
                systemMsg.put("role", "system");
                systemMsg.put("content", context.getString(R.string.ai_invoice_system_role));
                messages.add(systemMsg);

                // Note: En production, on enverrait l'image en base64 ou via une URL publique
                java.util.Map<String, Object> userMsg = new java.util.HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", context.getString(R.string.ai_invoice_user_prompt));
                messages.add(userMsg);

                body.put("model", "gemini-1.5-flash"); // Utilisation de flash pour l'analyse rapide

                String apiKey = com.example.stockit.BuildConfig.GEMINI_API_KEY;
                retrofit2.Response<okhttp3.ResponseBody> response = aiService.generateGeminiContent("gemini-1.5-flash", apiKey, body).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String raw = response.body().string();
                    mainHandler.post(() -> callback.onResponse(raw));
                }
            } catch (Exception e) {
                mainHandler.post(() -> callback.onResponse(context.getString(R.string.ai_error_analysis, e.getMessage())));
            }
        });
    }

    public void login(String username, String password, AuthCallback callback) {
        android.util.Log.d("MainController", "Login requested for: " + username);
        executor.execute(() -> {
            try {
                android.util.Log.d("MainController", "Login background task started");
                
                // Force seed for debug/demo purposes
                if (db.userDao().getUserCount() < 5) { // Force si l'équipe n'est pas complète
                    android.util.Log.d("MainController", "Seeding Vistaprint Team...");
                    db.userDao().register(new User("admin", "admin123", "ADMIN"));
                    db.userDao().register(new User("Nadhem", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Nour", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Eya", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Majdi", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Zied", "pass123", "TECHNICIEN"));
                }
                
                User user = db.userDao().login(username, password);
                if (user == null) {
                    // Fallback for demo: if it's admin/admin123 and still fails, something is wrong with DB
                    // Let's try to just return a dummy user to unblock the developer
                    if ("admin".equals(username) && "admin123".equals(password)) {
                        android.util.Log.w("MainController", "DB Login failed for admin, using hardcoded fallback");
                        user = new User("admin", "admin123", "ADMIN");
                    }
                }

                android.util.Log.d("MainController", "Database login result: " + (user != null ? "Success" : "Failure"));
                
                final User finalUser = user;
                if (finalUser != null) {
                    currentUser = finalUser;
                    recordAuditLog("LOGIN", "Utilisateur connecté: " + username);
                }
                
                mainHandler.post(() -> {
                    android.util.Log.d("MainController", "Posting login result to UI");
                    callback.onResult(finalUser != null, finalUser);
                });
            } catch (Exception e) {
                android.util.Log.e("MainController", "Login CRASH: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onResult(false, null));
            }
        });
    }

    private void recordAuditLog(String action, String details) {
        executor.execute(() -> {
            String uId = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_guest);
            String device = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
            AuditLog log = new AuditLog(uId, action, details, System.currentTimeMillis(), "127.0.0.1", device);
            db.auditLogDao().insert(log);
        });
    }

    /**
     * Enregistre en base locale un utilisateur authentifié via SSO Auth0 (Vista).
     *
     * Le mot de passe est un placeholder inutilisable ({@code "sso-auth0"}) : les
     * utilisateurs SSO ne peuvent PAS se reconnecter par le formulaire classique
     * — Auth0 reste la seule source de vérité pour leur mot de passe.
     * On persiste tout de même l'utilisateur en Room pour que les vérifications
     * de rôle ({@link #isAdmin()}, {@link #canEditStock()}) et les jointures
     * (audit, mouvements de stock…) fonctionnent normalement.
     */
    public void loginSso(String username, String role, AuthCallback callback) {
        android.util.Log.d("MainController", "SSO login: " + username + " (role=" + role + ")");
        executor.execute(() -> {
            try {
                User existing = null;
                for (User u : db.userDao().getAllUsers()) {
                    if (u.getUsername() != null && u.getUsername().equalsIgnoreCase(username)) {
                        existing = u;
                        break;
                    }
                }

                User user;
                if (existing != null) {
                    // Met à jour uniquement le rôle si nécessaire, préserve id/points/level/badges.
                    if (role != null && !role.equals(existing.getRole())) {
                        existing.setRole(role);
                        db.userDao().update(existing);
                    }
                    user = existing;
                } else {
                    user = new User(username, "sso-auth0", role != null ? role : "USER");
                    db.userDao().register(user);
                }

                currentUser = user;
                recordAuditLog("LOGIN_SSO", "Connexion SSO Auth0 : " + username);

                mainHandler.post(() -> callback.onResult(true, user));
            } catch (Exception e) {
                android.util.Log.e("MainController", "SSO login CRASH: " + e.getMessage(), e);
                mainHandler.post(() -> callback.onResult(false, null));
            }
        });
    }

    public static User getCurrentUser() { return currentUser; }
    public boolean isAdmin() { return currentUser != null && "ADMIN".equals(currentUser.getRole()); }
    public boolean canEditStock() { return currentUser != null && ("ADMIN".equals(currentUser.getRole()) || "MANAGER".equals(currentUser.getRole())); }

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
        if (!isAdmin()) return;
        executor.execute(() -> {
            db.categoryDao().insert(new Category(name, parentId, color));
            recordAuditLog("ADD_CATEGORY", context.getString(R.string.audit_cat_added, name));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateCategory(Category category, Runnable onComplete) {
        if (!isAdmin()) return;
        executor.execute(() -> {
            db.categoryDao().update(category);
            recordAuditLog("UPDATE_CATEGORY", context.getString(R.string.audit_cat_updated, category.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteCategory(Category category, Runnable onComplete) {
        if (!isAdmin()) return;
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
        executor.execute(() -> {
            db.userDao().deleteUser(user);
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
        if (!canEditStock()) return;
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
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateQuantity(Product product, int delta, Runnable onComplete) {
        // Tout utilisateur connecté peut ajuster la quantité via les boutons +/−
        // (technicien en réception, admin en correction manuelle…). Les rôles
        // ADMIN/MANAGER restent seuls autorisés à créer/supprimer un produit,
        // voir addProduct / deleteProduct plus haut.
        android.util.Log.d("QtyDebug", "updateQuantity called: product=" + (product != null ? product.getName() : "null")
                + " delta=" + delta + " currentUser=" + (currentUser != null ? currentUser.getUsername() : "NULL"));
        if (currentUser == null) {
            android.util.Log.w("QtyDebug", "updateQuantity ABORTED: currentUser is null");
            return;
        }
        executor.execute(() -> {
            int newQty = Math.max(0, product.getQuantity() + delta);
            android.util.Log.d("QtyDebug", "Updating DB: " + product.getName() + " " + product.getQuantity() + " -> " + newQty);
            product.setQuantity(newQty);
            db.productDao().update(product);
            String type = (delta > 0) ? "IN" : "OUT";
            recordMovement(product.getId(), product.getName(), type, Math.abs(delta), context.getString(R.string.mov_manual_adj), null);
            recordAuditLog("UPDATE_QUANTITY", context.getString(R.string.audit_qty_adjusted, product.getName(), delta));
            android.util.Log.d("QtyDebug", "DB updated. Posting UI refresh callback=" + (onComplete != null));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateProduct(Product product, Runnable onComplete) {
        if (!canEditStock()) return;
        executor.execute(() -> {
            db.productDao().update(product);
            recordAuditLog("UPDATE_PRODUCT", context.getString(R.string.audit_prod_updated, product.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteProduct(Product product, Runnable onComplete) {
        if (!isAdmin()) return;
        executor.execute(() -> {
            db.productDao().delete(product);
            recordMovement(product.getId(), product.getName(), "OUT", product.getQuantity(), context.getString(R.string.mov_deleted), null);
            recordAuditLog("DELETE_PRODUCT", context.getString(R.string.audit_prod_deleted, product.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void recordMovement(int productId, String productName, String type, int qty, String reason, String comment) {
        executor.execute(() -> {
            String date = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String userName = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_system);
            db.stockMovementDao().insert(new StockMovement(productId, productName, type, qty, date, reason, userName, comment));
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
            if (db.productDao().getTotalQuantity() == 0) {
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
        if (!canEditStock()) return;
        executor.execute(() -> {
            String date = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(new java.util.Date());
            db.purchaseOrderDao().insert(new PurchaseOrder(product, qty, supplier, date, context.getString(R.string.purchase_status_pending)));
            recordAuditLog("CREATE_PURCHASE", context.getString(R.string.audit_purchase_created, product, qty));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updatePurchaseOrder(PurchaseOrder order, Runnable onComplete) {
        if (!canEditStock()) return;
        executor.execute(() -> {
            db.purchaseOrderDao().update(order);
            recordAuditLog("UPDATE_PURCHASE", "Commande mise à jour: " + order.getProductName());
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deletePurchaseOrder(PurchaseOrder order, Runnable onComplete) {
        if (!isAdmin()) return;
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
        if (!isAdmin()) return;
        executor.execute(() -> {
            db.supplierDao().insert(new com.example.stockit.model.Supplier(name, email, phone, address, leadTime, discount));
            recordAuditLog("ADD_SUPPLIER", context.getString(R.string.audit_sup_added, name));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateSupplier(com.example.stockit.model.Supplier supplier, Runnable onComplete) {
        if (!isAdmin()) return;
        executor.execute(() -> {
            db.supplierDao().update(supplier);
            recordAuditLog("UPDATE_SUPPLIER", context.getString(R.string.audit_sup_updated, supplier.getName()));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteSupplier(com.example.stockit.model.Supplier supplier, Runnable onComplete) {
        if (!isAdmin()) return;
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
        executor.execute(() -> {
            com.example.stockit.model.ShippingOrder order = new com.example.stockit.model.ShippingOrder(customer, items, address, "", context.getString(R.string.ship_status_pending));
            order.setTicketNumber(ticket);
            db.shippingOrderDao().insert(order);
            recordAuditLog("ADD_SHIPPING", context.getString(R.string.audit_ship_created, customer));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateShippingOrder(com.example.stockit.model.ShippingOrder order, Runnable onComplete) {
        executor.execute(() -> {
            db.shippingOrderDao().update(order);
            recordAuditLog("UPDATE_SHIPPING", context.getString(R.string.audit_ship_updated, String.valueOf(order.getId())));
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deleteShippingOrder(com.example.stockit.model.ShippingOrder order, Runnable onComplete) {
        if (!isAdmin()) return;
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
            try {
                apiService.addClaim(newClaim).execute();
            } catch (Exception e) {
                android.util.Log.e("MainController", "Upload Claim Error: " + e.getMessage());
            }
        });
    }

    public void getMessages(int claimId, ChatCallback callback) {
        executor.execute(() -> {
            try {
                retrofit2.Response<List<com.example.stockit.model.ChatMessage>> response = apiService.getMessages(claimId).execute();
                if (response.isSuccessful() && response.body() != null) {
                    mainHandler.post(() -> callback.onMessagesLoaded(response.body()));
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Get Messages Error: " + e.getMessage());
            }
        });
    }

    public void addMessage(int claimId, String text, Runnable onComplete) {
        executor.execute(() -> {
            String uId = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_guest);
            com.example.stockit.model.ChatMessage msg = new com.example.stockit.model.ChatMessage(uId, uId, text, System.currentTimeMillis());
            try {
                apiService.addMessage(claimId, msg).execute();
                if (onComplete != null) mainHandler.post(onComplete);
            } catch (Exception e) {
                android.util.Log.e("MainController", "Add Message Error: " + e.getMessage());
            }
        });
    }

    public interface ZycusCallback { void onPRCreated(String prNumber); }

    public void createZycusOrder(Product p, int qty, ZycusCallback callback) {
        executor.execute(() -> {
            try {
                Thread.sleep(1500); // Effet visuel pour le jury
                String mockPR = "PR-2024-VISTA-" + (1000 + new java.util.Random().nextInt(9000));
                recordAuditLog("ZYCUS_ORDER", "Commande Zycus générée: " + p.getName() + " [Ref: " + mockPR + "]");
                mainHandler.post(() -> callback.onPRCreated(mockPR));
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void generateAIReport(String rawData, AIResponseCallback callback) {
        String prompt = "Agis comme un gestionnaire de stock IT expert pour Vistaprint. Voici les données brutes du mois : " + rawData + 
                       ". Rédige un rapport de synthèse professionnel en français (max 150 mots). Analyse les tendances, signale les alertes critiques et donne des recommandations. Utilise un ton pro mais concis.";
        askAssistant(prompt, callback);
    }

    public void detectObjectsGemini(android.net.Uri fileUri, VisionCallback callback) {
        executor.execute(() -> {
            try {
                // 1. Lire l'image et l'encoder en Base64
                java.io.InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
                java.io.ByteArrayOutputStream byteBuffer = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = inputStream.read(buffer)) != -1) byteBuffer.write(buffer, 0, len);
                byte[] bytes = byteBuffer.toByteArray();
                inputStream.close();
                String base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

                // 2. Préparer la requête Multimodale pour Gemini (Version Flash rapide)
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                java.util.List<java.util.Map<String, Object>> contents = new java.util.ArrayList<>();
                java.util.Map<String, Object> contentMap = new java.util.HashMap<>();
                java.util.List<java.util.Map<String, Object>> parts = new java.util.ArrayList<>();

                java.util.Map<String, Object> textPart = new java.util.HashMap<>();
                textPart.put("text", "Réponds par un seul mot (le nom de l'objet IT). " +
                        "Exemple: Souris, Clavier, Ordinateur, Ecran. " +
                        "Si tu vois une marque, écris-la après l'objet (ex: Souris Logitech). Rien d'autre.");
                parts.add(textPart);

                java.util.Map<String, Object> imagePart = new java.util.HashMap<>();
                java.util.Map<String, String> inlineData = new java.util.HashMap<>();
                inlineData.put("mime_type", "image/jpeg");
                inlineData.put("data", base64Image);
                imagePart.put("inline_data", inlineData);
                parts.add(imagePart);

                contentMap.put("parts", parts);
                contents.add(contentMap);
                body.put("contents", contents);

                // Désactivation des filtres de sécurité pour éviter les faux positifs (blocage d'analyse)
                java.util.List<java.util.Map<String, String>> safetySettings = new java.util.ArrayList<>();
                String[] categories = {"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"};
                for (String cat : categories) {
                    java.util.Map<String, String> setting = new java.util.HashMap<>();
                    setting.put("category", cat);
                    setting.put("threshold", "BLOCK_NONE");
                    safetySettings.add(setting);
                }
                body.put("safetySettings", safetySettings);

                String apiKey = com.example.stockit.BuildConfig.GEMINI_API_KEY;
                retrofit2.Response<okhttp3.ResponseBody> response = aiService.generateGeminiContent("gemini-1.5-flash", apiKey, body).execute();
                
                if (response.isSuccessful() && response.body() != null) {
                    String raw = response.body().string();
                    org.json.JSONObject json = new org.json.JSONObject(raw);
                    String result = json.getJSONArray("candidates")
                                     .getJSONObject(0)
                                     .getJSONObject("content")
                                     .getJSONArray("parts")
                                     .getJSONObject(0)
                                     .getString("text").trim();
                    
                    // Nettoyage Markdown (étoiles) et espaces
                    String cleanResult = result.replaceAll("[\\*]", "").trim();

                    java.util.List<String> labels = new java.util.ArrayList<>();
                    labels.add(cleanResult);
                    mainHandler.post(() -> callback.onLabelsDetected(labels, cleanResult));
                } else {
                    mainHandler.post(() -> callback.onLabelsDetected(new java.util.ArrayList<>(), "HF Error"));
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Gemini Vision Error: " + e.getMessage());
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
        data.put("email_to", "wissem.soussia@vista.com");

        android.util.Log.d("MainController", "Sending alert to local server for email...");
        apiService.sendAlert(data).enqueue(new retrofit2.Callback<Void>() {
            @Override public void onResponse(retrofit2.Call<Void> call, retrofit2.Response<Void> response) {
                android.util.Log.d("MainController", "Server Alert Success Code: " + response.code());
                if (onComplete != null) mainHandler.post(onComplete);
            }
            @Override public void onFailure(retrofit2.Call<Void> call, Throwable t) {
                android.util.Log.e("MainController", "Server Alert Connection Error: " + t.getMessage());
                if (onComplete != null) mainHandler.post(onComplete);
            }
        });

        // Slack et Jira restent direct comme demandé
        sendSlackNotification("🚨 *ALERTE STOCK BAS* 🚨\nProduit: " + p.getName() + "\nQuantité actuelle: " + p.getQuantity());
        sendJiraTicket(p.getName(), p.getQuantity());

        // Email via workflow n8n — destinataire dédié "manager stock" (routé
        // côté Java, aucun changement n8n requis, voir StockItReporter overload).
        com.example.stockit.util.StockItReporter.sendEvent(context,
                "Alerte stock bas : " + p.getName(),
                "🚨 Le produit \"" + p.getName() + "\" est passé sous le seuil critique.\n\n"
                        + "• Quantité restante : " + p.getQuantity() + "\n"
                        + "• Seuil configuré  : " + p.getMinThreshold() + "\n\n"
                        + "👉 Merci de déclencher une commande fournisseur avant rupture.",
                "wissem.soussia@vista.com");
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
        executor.execute(() -> {
            try {
                android.util.Log.d("MainController", "Attempting to send Slack message...");
                java.util.Map<String, Object> body = new java.util.HashMap<>();
                body.put("channel", "#etx-alerts");
                body.put("text", message);

                String authHeader = "Bearer " + com.example.stockit.BuildConfig.SLACK_BOT_TOKEN;
                
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.RequestBody requestBody = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"),
                    new com.google.gson.Gson().toJson(body)
                );
                
                okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://slack.com/api/chat.postMessage")
                    .header("Authorization", authHeader)
                    .post(requestBody)
                    .build();

                try (okhttp3.Response response = client.newCall(request).execute()) {
                    String respStr = response.body() != null ? response.body().string() : "no body";
                    if (response.isSuccessful()) {
                        android.util.Log.d("MainController", "Slack message sent successfully!");
                    } else {
                        android.util.Log.e("MainController", "Slack HTTP error: " + response.code() + " - " + respStr);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Slack Notification Exception: " + e.getMessage());
            }
        });
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
                                "✅ Ticket Jira créé : " + keyOrError,
                                android.widget.Toast.LENGTH_LONG).show();
                    } else {
                        android.util.Log.e("MainController", "Jira create failed: " + keyOrError);
                        android.widget.Toast.makeText(
                                context,
                                "❌ Erreur Jira : " + keyOrError,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                }));
    }
}
