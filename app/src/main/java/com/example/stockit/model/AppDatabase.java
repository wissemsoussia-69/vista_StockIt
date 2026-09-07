package com.example.stockit.model;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {Product.class, User.class, PurchaseOrder.class, StockMovement.class, Category.class, AuditLog.class, Supplier.class, ShippingOrder.class, Quest.class, AnalyzedTicket.class, AnalyticsEvent.class, AlertEvent.class}, version = 25, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase instance;

    public abstract ProductDao productDao();
    public abstract UserDao userDao();
    public abstract PurchaseOrderDao purchaseOrderDao();
    public abstract StockMovementDao stockMovementDao();
    public abstract CategoryDao categoryDao();
    public abstract AuditLogDao auditLogDao();
    public abstract SupplierDao supplierDao();
    public abstract ShippingOrderDao shippingOrderDao();
    public abstract QuestDao questDao();
    public abstract AnalyzedTicketDao analyzedTicketDao();
    public abstract AnalyticsEventDao analyticsEventDao();
    public abstract AlertEventDao alertEventDao();

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                    AppDatabase.class, "stock_database")
                    .fallbackToDestructiveMigration()
                    .build();
        }
        return instance;
    }
}
