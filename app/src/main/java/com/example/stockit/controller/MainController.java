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
import com.example.stockit.model.ApiService;
import com.example.stockit.model.AlertEvent;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;


import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
    public interface VisionCallback { void onLabelsDetected(List<String> labels, String fullText); }
    public interface QuestCallback { void onQuestsLoaded(List<com.example.stockit.model.Quest> quests); }
    public interface AlertHistoryCallback { void onAlertsLoaded(List<AlertEvent> events); }
    public interface ChannelResultCallback { void onResult(boolean success, String details); }

    private static volatile MainController INSTANCE;

    public static MainController getInstance(Context anyContext) {
        MainController local = INSTANCE;
        if (local == null) {
            synchronized (MainController.class) {
                local = INSTANCE;
                if (local == null) {
                    local = new MainController(anyContext.getApplicationContext());
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    @Deprecated
    public MainController(Context context) {
        this.context = context.getApplicationContext();
        android.util.Log.d("MainController", "Initializing MainController (context="
                + context.getClass().getSimpleName() + ")");
        this.db = AppDatabase.getInstance(this.context);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());

        if (currentUser == null) {
            com.example.stockit.util.SessionManager session = com.example.stockit.util.SessionManager.get(this.context);
            if (session.isLoggedIn() && session.getUsername() != null) {
                String uname = session.getUsername();
                String role = session.getRole() != null ? session.getRole() : "USER";
                User restored = new User(uname, "sso-restored", role);
                currentUser = restored;
                android.util.Log.i("MainController", "currentUser restaure depuis SessionManager : "
                        + uname + " (role=" + role + ")");
            }
        }

        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor(new com.example.stockit.util.AuthBearerInterceptor(this.context))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("http://10.0.2.2/api/") // Fallback Emulator
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        this.apiService = retrofit.create(ApiService.class);

        Retrofit aiRetrofit = new Retrofit.Builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta/")
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        this.aiService = aiRetrofit.create(com.example.stockit.model.AIService.class);
    }

    public void askAssistant(String question, AIResponseCallback callback) {
        executor.execute(() -> {
            try {
                String baseUrl = com.example.stockit.BuildConfig.GATEWAY_URL;
                String key     = com.example.stockit.BuildConfig.CIMPRESS_GATEWAY_KEY;
                String model   = com.example.stockit.BuildConfig.CIMPRESS_VISION_MODEL;

                if (baseUrl == null || baseUrl.isEmpty() || key == null || key.isEmpty()) {
                        mainHandler.post(() -> callback.onResponse(
                            "AI assistant not configured (Cimpress Gateway missing)."));
                    return;
                }

                String prompt = context.getString(R.string.ai_system_role)
                        + "\n\nQuestion: " + question;

                org.json.JSONObject msg = new org.json.JSONObject();
                msg.put("role", "user");
                msg.put("content", prompt);
                org.json.JSONArray messages = new org.json.JSONArray();
                messages.put(msg);
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("model", model);
                payload.put("max_tokens", 512);
                payload.put("messages", messages);

                okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build();

                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(baseUrl.replaceAll("/$", "") + "/chat/completions")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .post(okhttp3.RequestBody.create(payload.toString(), JSON))
                        .build();

                try (okhttp3.Response resp = client.newCall(req).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful()) {
                        android.util.Log.w("MainController", "Cimpress chat HTTP "
                                + resp.code() + " -> " + body);
                        mainHandler.post(() -> callback.onResponse(
                            "Sorry, the AI assistant is unavailable (code "
                                + resp.code() + ")."));
                        return;
                    }
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    org.json.JSONArray choices = json.optJSONArray("choices");
                    String text = null;
                    if (choices != null && choices.length() > 0) {
                        org.json.JSONObject message = choices.optJSONObject(0)
                                .optJSONObject("message");
                        if (message != null) {
                            Object contentObj = message.opt("content");
                            if (contentObj instanceof String) {
                                text = (String) contentObj;
                            } else if (contentObj instanceof org.json.JSONArray) {
                                org.json.JSONArray arr = (org.json.JSONArray) contentObj;
                                StringBuilder sb = new StringBuilder();
                                for (int i = 0; i < arr.length(); i++) {
                                    Object item = arr.opt(i);
                                    if (item instanceof org.json.JSONObject) {
                                        String t = ((org.json.JSONObject) item)
                                                .optString("text", "").trim();
                                        if (!t.isEmpty()) {
                                            if (sb.length() > 0) sb.append('\n');
                                            sb.append(t);
                                        }
                                    } else if (item instanceof String) {
                                        String t = ((String) item).trim();
                                        if (!t.isEmpty()) {
                                            if (sb.length() > 0) sb.append('\n');
                                            sb.append(t);
                                        }
                                    }
                                }
                                if (sb.length() > 0) text = sb.toString();
                            }
                        }
                    }
                    if (text == null || text.trim().isEmpty()) {
                        text = "The AI response is empty or unreadable.";
                    }
                    final String responseText = text;
                    mainHandler.post(() -> callback.onResponse(responseText));
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "askAssistant error", e);
                mainHandler.post(() -> callback.onResponse(
                    "Sorry, an error occurred with AI."));
            }
        });
    }


    public void login(String username, String password, AuthCallback callback) {
        android.util.Log.d("MainController", "Login requested for: " + username);
        executor.execute(() -> {
            try {
                android.util.Log.d("MainController", "Login background task started");
                
                if (db.userDao().getUserCount() < 5) { // Seed if the team list is incomplete
                    android.util.Log.d("MainController", "Seeding Vistaprint team...");
                    db.userDao().register(new User("admin", "admin123", "ADMIN"));
                    db.userDao().register(new User("Nadhem", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Nour", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Eya", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Majdi", "pass123", "TECHNICIEN"));
                    db.userDao().register(new User("Zied", "pass123", "TECHNICIEN"));
                }
                
                User user = db.userDao().login(username, password);
                if (user == null) {
                    if ("admin".equals(username) && "admin123".equals(password)) {
                        android.util.Log.w("MainController", "DB Login failed for admin, using hardcoded fallback");
                        user = new User("admin", "admin123", "ADMIN");
                    }
                }

                android.util.Log.d("MainController", "Database login result: " + (user != null ? "Success" : "Failure"));
                
                final User finalUser = user;
                if (finalUser != null) {
                    currentUser = finalUser;
                    recordAuditLog("LOGIN", "User logged in: " + username);
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
                recordAuditLog("LOGIN_SSO", "Auth0 SSO login: " + username);

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

    public void addProduct(String name, String category, Integer categoryId, String description, String assetTag,
                           int quantity, double unitPrice, String mfgDate, String expDate, String reason,
                           String poNumber, String poDescription, String receivedFrom,
                           Runnable onComplete) {
        addProduct(name, category, categoryId, description, assetTag, quantity, unitPrice, mfgDate, expDate, reason,
                poNumber, poDescription, receivedFrom, null, null, null, onComplete);
    }

    public void addProduct(String name, String category, Integer categoryId, String description, String assetTag,
                           int quantity, double unitPrice, String mfgDate, String expDate, String reason,
                           String poNumber, String poDescription, String receivedFrom,
                           String articleNumber, String brand, String packagePoNumber,
                           Runnable onComplete) {
        if (currentUser == null) {
            android.util.Log.w("ScanAsset", "addProduct ABORTED: currentUser is null");
            return;
        }
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
            addPoints(10, "Product added");
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void updateQuantity(Product product, int delta, Runnable onComplete) {
        android.util.Log.d("QtyDebug", "updateQuantity called: product=" + (product != null ? product.getName() : "null")
                + " delta=" + delta + " currentUser=" + (currentUser != null ? currentUser.getUsername() : "NULL"));
        if (currentUser == null) {
            android.util.Log.w("QtyDebug", "updateQuantity ABORTED: currentUser is null");
            return;
        }
        executor.execute(() -> {
            int previousQty = product.getQuantity();
            int newQty = Math.max(0, product.getQuantity() + delta);
            android.util.Log.d("QtyDebug", "Updating DB: " + product.getName() + " " + product.getQuantity() + " -> " + newQty);
            product.setQuantity(newQty);
            db.productDao().update(product);
            String type = (delta > 0) ? "IN" : "OUT";
            recordMovement(product.getId(), product.getName(), type, Math.abs(delta), context.getString(R.string.mov_manual_adj), null);
            recordAuditLog("UPDATE_QUANTITY", context.getString(R.string.audit_qty_adjusted, product.getName(), delta));
            if (delta < 0 && shouldSendAutomaticLowStockAlert(previousQty, newQty, product.getMinThreshold())) {
                triggerN8nAlert(product, null);
            }
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

    public void recordExitToTicket(final Product product, final int qty,
                                   final String ticketId, final String assignmentReason,
                                   final Runnable onSuccess, final Runnable onInsufficientStock) {
        if (product == null || qty <= 0) return;
        executor.execute(() -> {
            Product fresh = db.productDao().getById(product.getId());
            if (fresh == null) { if (onInsufficientStock != null) mainHandler.post(onInsufficientStock); return; }
            if (fresh.getQuantity() < qty) {
                if (onInsufficientStock != null) mainHandler.post(onInsufficientStock);
                return;
            }
            int previousQty = fresh.getQuantity();
            fresh.setQuantity(fresh.getQuantity() - qty);
            db.productDao().update(fresh);

            String date = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date());
            String userName = (currentUser != null) ? currentUser.getUsername() : context.getString(R.string.user_name_system);
                String reason = "Outbound on ticket " + ticketId
                    + (assignmentReason != null && !assignmentReason.isEmpty() ? " - " + assignmentReason : "");
            StockMovement mv = new StockMovement(fresh.getId(), fresh.getName(), "OUT", qty, date, reason, userName, null);
            mv.setTicketId(ticketId);
            mv.setAssignmentReason(assignmentReason);
            db.stockMovementDao().insert(mv);

            recordAuditLog("EXIT_TO_TICKET", fresh.getName() + " x" + qty + " -> " + ticketId);
            addPoints(5, "Ticket outbound");
            if (shouldSendAutomaticLowStockAlert(previousQty, fresh.getQuantity(), fresh.getMinThreshold())) {
                triggerN8nAlert(fresh, null);
            }
            if (onSuccess != null) mainHandler.post(onSuccess);
        });
    }

    private boolean shouldSendAutomaticLowStockAlert(int previousQty, int newQty, int threshold) {
        return newQty != previousQty && newQty <= threshold;
    }


    public interface AnalyzedTicketsCallback {
        void onLoaded(java.util.List<com.example.stockit.model.AnalyzedTicket> analyzed);
    }
    public interface FulfilledIdsCallback {
        void onLoaded(java.util.List<String> ticketIds);
    }

    private static String equipmentKeyOf(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

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

    public void recordDeliveryToTicket(final String ticketId, final String equipmentName,
                                       final int qty, final String reasonIfManual) {
        if (ticketId == null || ticketId.isEmpty() || qty <= 0) return;
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
                t.setReason(reasonIfManual != null ? reasonIfManual : "Manual selection");
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

    public void getFullyFulfilledTicketIds(final FulfilledIdsCallback cb) {
        executor.execute(() -> {
            java.util.List<String> ids = db.analyzedTicketDao().getFullyFulfilledTicketIds();
            mainHandler.post(() -> cb.onLoaded(ids != null ? ids : new java.util.ArrayList<>()));
        });
    }

    public void getAnalyzedTickets(final AnalyzedTicketsCallback cb) {
        executor.execute(() -> {
            java.util.List<com.example.stockit.model.AnalyzedTicket> list =
                    db.analyzedTicketDao().getAll();
            mainHandler.post(() -> cb.onLoaded(list != null ? list : new java.util.ArrayList<>()));
        });
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

    public void getAlertEvents(AlertHistoryCallback callback) {
        executor.execute(() -> {
            List<AlertEvent> events = db.alertEventDao().getRecent(300);
            mainHandler.post(() -> callback.onAlertsLoaded(events));
        });
    }

    public void getReportData(ReportCallback callback) {
        executor.execute(() -> {
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
                db.productDao().insert(new Product("Laptop Dell Latitude", "IT", "Laptop", "DELL-LAT-01", 15, 1200.0, "10/01/2024", ""));
                db.productDao().insert(new Product("Logitech MX Mouse", "Peripheral", "Ergonomic mouse", "LOGI-MX-02", 4, 85.0, "15/01/2024", ""));
                db.productDao().insert(new Product("HP 24-inch Monitor", "IT", "Full HD monitor", "HP-SCR-24", 2, 180.0, "20/01/2024", ""));
                
                db.stockMovementDao().insert(new StockMovement(1, "Laptop Dell Latitude", "IN", 15, "10/06/2024", "Receiving", "admin", "Stock arrival"));
                db.stockMovementDao().insert(new StockMovement(3, "HP 24-inch Monitor", "OUT", 1, "12/06/2024", "Loan", "Thomas", "Outbound for office 302"));
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
            recordAuditLog("UPDATE_PURCHASE", "Purchase order updated: " + order.getProductName());
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

    public void deletePurchaseOrder(PurchaseOrder order, Runnable onComplete) {
        if (!isAdmin()) return;
        executor.execute(() -> {
            db.purchaseOrderDao().delete(order);
            recordAuditLog("DELETE_PURCHASE", "Purchase order deleted: " + order.getProductName());
            if (onComplete != null) mainHandler.post(onComplete);
        });
    }

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

    public void getAIInsights(AICallback callback) {
        generateAIInsights(callback);
    }

    public void generateAIInsights(AICallback callback) {
        executor.execute(() -> {
            List<Product> products = db.productDao().getAll();
            List<StockMovement> movements = db.stockMovementDao().getAll();
            List<com.example.stockit.model.AIInsight> insights = new java.util.ArrayList<>();

            for (Product p : products) {
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

                for (StockMovement m : movements) {
                    if (m.getProductId() == p.getId() && m.getQuantity() > 20) {
                        insights.add(new com.example.stockit.model.AIInsight(
                            context.getString(R.string.ai_insight_anomaly),
                            context.getString(R.string.ai_insight_anomaly_desc, m.getQuantity(), p.getName()),
                            com.example.stockit.model.AIInsight.Type.ANOMALY, "MEDIUM"
                        ));
                    }
                }

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
                Thread.sleep(1500); // Demo visual delay
                String mockPR = "PR-2024-VISTA-" + (1000 + new java.util.Random().nextInt(9000));
                recordAuditLog("ZYCUS_ORDER", "Zycus order generated: " + p.getName() + " [Ref: " + mockPR + "]");
                mainHandler.post(() -> callback.onPRCreated(mockPR));
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    public void generateAIReport(String rawData, AIResponseCallback callback) {
        String prompt = "Act as an expert IT inventory manager for Vistaprint. Here is the monthly raw data: " + rawData +
                   ". Write a professional summary report in English (max 150 words). Analyze trends, highlight critical alerts, and provide recommendations. Use a concise professional tone.";
        askAssistant(prompt, callback);
    }


    public void triggerN8nAlert(Product p, Runnable onComplete) {
        executor.execute(() -> {
            StockHealthSnapshot health = computeStockHealthSnapshot(p);
                String alertMessage = "LOW STOCK ALERT\nProduct: " + p.getName()
                    + "\nCurrent quantity: " + p.getQuantity()
                    + "\nStatic threshold: " + p.getMinThreshold()
                    + "\nSmart threshold: " + health.dynamicThreshold
                    + "\nOut-of-stock risk: " + health.predictedDaysLabel;

            long localAlertId = createAlertEvent(p, health, "LOCAL_API", "PENDING", alertMessage, "Waiting for local API send");
            long slackAlertId = createAlertEvent(p, health, "SLACK", "PENDING", alertMessage, "Waiting for Slack send");
            long jiraAlertId = createAlertEvent(p, health, "JIRA", "PENDING", "[StockIT] Stock Alert: " + p.getName(), "Waiting for Jira creation");
            long emailAlertId = createAlertEvent(p, health, "EMAIL", "PENDING", "Low stock alert: " + p.getName(), "Waiting for webhook send");

            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("event", "LOW_STOCK_ALERT");
            data.put("productName", p.getName());
            data.put("currentQty", p.getQuantity());
            data.put("threshold", p.getMinThreshold());
            data.put("dynamicThreshold", health.dynamicThreshold);
            data.put("predictedDays", health.predictedDays);
            data.put("user", currentUser != null ? currentUser.getUsername() : context.getString(R.string.user_name_system));
            data.put("timestamp", System.currentTimeMillis());
            data.put("email_to", "wissem.soussia@vista.com");

            android.util.Log.d("MainController", "Sending alert to local server for email...");
            apiService.sendAlert(data).enqueue(new retrofit2.Callback<Void>() {
                @Override public void onResponse(retrofit2.Call<Void> call, retrofit2.Response<Void> response) {
                    android.util.Log.d("MainController", "Server Alert Success Code: " + response.code());
                    if (response.isSuccessful()) {
                        markAlertEvent(localAlertId, "SENT", "HTTP " + response.code());
                    } else {
                        markAlertEvent(localAlertId, "FAILED", "HTTP " + response.code());
                    }
                    if (onComplete != null) mainHandler.post(onComplete);
                }
                @Override public void onFailure(retrofit2.Call<Void> call, Throwable t) {
                    android.util.Log.e("MainController", "Server Alert Connection Error: " + t.getMessage());
                    markAlertEvent(localAlertId, "FAILED", t.getMessage());
                    if (onComplete != null) mainHandler.post(onComplete);
                }
            });

            sendSlackNotification(alertMessage, (success, details) ->
                    markAlertEvent(slackAlertId, success ? "SENT" : "FAILED", details));

            sendJiraTicket(p.getName(), p.getQuantity(), (success, details) ->
                    markAlertEvent(jiraAlertId, success ? "SENT" : "FAILED", details));

                com.example.stockit.util.StockItReporter.sendEvent(context,
                    "Low stock alert: " + p.getName(),
                    "Product \"" + p.getName() + "\" dropped below the critical threshold.\n\n"
                        + "- Remaining quantity: " + p.getQuantity() + "\n"
                        + "- Configured threshold: " + p.getMinThreshold() + "\n"
                        + "- Smart threshold: " + health.dynamicThreshold + "\n"
                        + "- Estimated stockout: " + health.predictedDaysLabel + "\n\n"
                        + "Please trigger a supplier order before stockout.",
                    "wissem.soussia@vista.com",
                    (success, bodyOrError) -> markAlertEvent(
                            emailAlertId,
                            success ? "SENT" : "FAILED",
                            bodyOrError != null ? bodyOrError : "no_details"));
        });
    }

    private static final class StockHealthSnapshot {
        final int dynamicThreshold;
        final int predictedDays;
        final String predictedDaysLabel;

        StockHealthSnapshot(int dynamicThreshold, int predictedDays, String predictedDaysLabel) {
            this.dynamicThreshold = dynamicThreshold;
            this.predictedDays = predictedDays;
            this.predictedDaysLabel = predictedDaysLabel;
        }
    }

    private StockHealthSnapshot computeStockHealthSnapshot(Product p) {
        List<StockMovement> movements = db.stockMovementDao().getByProduct(p.getId());
        long now = System.currentTimeMillis();
        long windowStart = now - TimeUnit.DAYS.toMillis(30);
        double outQty = 0.0;
        for (StockMovement m : movements) {
            if (!"OUT".equalsIgnoreCase(m.getType())) continue;
            long ts = parseMovementDate(m.getDate());
            if (ts >= windowStart) {
                outQty += Math.max(0, m.getQuantity());
            }
        }

        double avgDailyOut = outQty / 30.0;
        double criticality = inferCriticality(p);
        int leadDays = inferLeadTimeDays(p);
        int dynamicThreshold = Math.max(p.getMinThreshold(), (int) Math.ceil(avgDailyOut * leadDays * criticality));

        int predictedDays;
        String label;
        if (avgDailyOut <= 0.01) {
            predictedDays = -1;
            label = "stable";
        } else {
            predictedDays = (int) Math.floor(p.getQuantity() / avgDailyOut);
            label = predictedDays + " d";
        }
        return new StockHealthSnapshot(dynamicThreshold, predictedDays, label);
    }

    private double inferCriticality(Product p) {
        String name = (p.getName() == null ? "" : p.getName()).toLowerCase(Locale.ROOT);
        if (name.contains("laptop") || name.contains("computer") || name.contains("pc")) return 2.0;
        if (name.contains("screen") || name.contains("monitor")) return 1.6;
        return 1.2;
    }

    private int inferLeadTimeDays(Product p) {
        String category = (p.getCategory() == null ? "" : p.getCategory()).toLowerCase(Locale.ROOT);
        if (category.contains("it")) return 10;
        return 7;
    }

    private long parseMovementDate(String dateText) {
        if (dateText == null || dateText.isEmpty()) return 0L;
        String[] patterns = {"dd/MM/yyyy HH:mm", "dd/MM/yyyy"};
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(pattern, Locale.getDefault());
                sdf.setLenient(false);
                java.util.Date d = sdf.parse(dateText);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    private long createAlertEvent(Product p,
                                  StockHealthSnapshot health,
                                  String channel,
                                  String status,
                                  String message,
                                  String details) {
        long now = System.currentTimeMillis();
        AlertEvent event = new AlertEvent(
                p.getName(),
                p.getQuantity(),
                p.getMinThreshold(),
                health.dynamicThreshold,
                health.predictedDays,
                channel,
                status,
                message,
                details,
                now,
                now);
        return db.alertEventDao().insert(event);
    }

    private void markAlertEvent(long alertId, String status, String details) {
        if (alertId <= 0) return;
        executor.execute(() -> db.alertEventDao().updateStatus(alertId, status, details, System.currentTimeMillis()));
    }

    public void addPoints(int points, String reason) {
        if (currentUser == null) return;
        executor.execute(() -> {
            currentUser.setPoints(currentUser.getPoints() + points);
            int newLevel = (currentUser.getPoints() / 100) + 1;
            if (newLevel > currentUser.getLevel()) {
                currentUser.setLevel(newLevel);
                recordAuditLog("LEVEL_UP", "Level reached: " + newLevel);
            }
            db.userDao().update(currentUser); // Update user in DB
            
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
                    recordAuditLog("QUEST_DONE", "Quest completed: " + q.getTitle());
                } else {
                    db.questDao().update(q);
                }
            }
        });
    }

    public void getQuests(QuestCallback callback) {
        executor.execute(() -> {
            if (db.questDao().getQuestCount() == 0) {
                db.questDao().insert(new com.example.stockit.model.Quest("Stock Pioneer", "Add 5 products to stock", 5, 50, "Bronze Badge"));
                db.questDao().insert(new com.example.stockit.model.Quest("Inspecteur Expert", "Scannez 10 objets avec l'IA", 10, 100, "Badge Argent"));
            }
            List<com.example.stockit.model.Quest> quests = db.questDao().getActiveQuests();
            mainHandler.post(() -> callback.onQuestsLoaded(quests));
        });
    }

    public void getLeaderboard(UserCallback callback) {
        executor.execute(() -> {
            List<com.example.stockit.model.User> topUsers = db.userDao().getAllUsers();
            topUsers.sort((u1, u2) -> Integer.compare(u2.getPoints(), u1.getPoints()));
            mainHandler.post(() -> callback.onUsersLoaded(topUsers));
        });
    }

    public void sendSlackNotification(String message, ChannelResultCallback cb) {
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
                        if (cb != null) cb.onResult(true, "HTTP " + response.code());
                    } else {
                        android.util.Log.e("MainController", "Slack HTTP error: " + response.code() + " - " + respStr);
                        if (cb != null) cb.onResult(false, "HTTP " + response.code() + " " + respStr);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("MainController", "Slack Notification Exception: " + e.getMessage());
                if (cb != null) cb.onResult(false, e.getMessage());
            }
        });
    }

    public void sendJiraTicket(String productName, int qty, ChannelResultCallback cb) {
        String token = com.example.stockit.BuildConfig.JIRA_API_TOKEN;
        if (token == null || token.isEmpty() || token.equals("YOUR_JIRA_TOKEN_HERE")) {
            android.util.Log.w("MainController", "Jira Token missing. Skipping ticket creation.");
            if (cb != null) cb.onResult(false, "jira_token_missing");
            return;
        }

        String projectKey  = com.example.stockit.BuildConfig.JIRA_PROJECT_KEY;
        String summary     = "[StockIT] Stock Alert: " + productName;
        String description = "Automatic alert: quantity of " + productName
            + " dropped to " + qty + ".";

        com.example.stockit.util.JiraClient.createTask(
                projectKey,
                summary,
                description,
                (success, keyOrError) -> mainHandler.post(() -> {
                    if (success) {
                        android.widget.Toast.makeText(
                                context,
                                "Jira ticket created: " + keyOrError,
                                android.widget.Toast.LENGTH_LONG).show();
                        if (cb != null) cb.onResult(true, keyOrError);
                    } else {
                        android.util.Log.e("MainController", "Jira create failed: " + keyOrError);
                        android.widget.Toast.makeText(
                                context,
                                "Jira error: " + keyOrError,
                                android.widget.Toast.LENGTH_LONG).show();
                        if (cb != null) cb.onResult(false, keyOrError);
                    }
                }));
    }
}
