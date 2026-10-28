package com.example.stockit.model;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {Product.class, User.class, PurchaseOrder.class, StockMovement.class, Category.class, AuditLog.class, Supplier.class, ShippingOrder.class, Claim.class, Quest.class, AnalyzedTicket.class}, version = 23, exportSchema = false)
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
    public abstract ClaimDao claimDao();
    public abstract QuestDao questDao();
    public abstract AnalyzedTicketDao analyzedTicketDao();

    /**
     * v22 → v23 (durcissement des comptes, cf. THREAT_MODEL TM-08 / TM-22) :
     *  - colonnes authSubject / provider ajoutées ;
     *  - les mots de passe historiques en clair (comptes semés) sont vidés —
     *    ils ne vérifieront jamais avec le hacheur PBKDF2 ;
     *  - le compte « admin » semé avec un mot de passe en clair est rétrogradé à USER ;
     *  - les anciennes lignes SSO (valeur sentinel de l'ancien format) sont marquées
     *    provider auth0 sans sujet : elles seront adoptées à la première connexion
     *    SSO du même employé, puis jamais plus.
     */
    static final Migration MIGRATION_22_23 = new Migration(22, 23) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE users ADD COLUMN authSubject TEXT");
            db.execSQL("ALTER TABLE users ADD COLUMN provider TEXT NOT NULL DEFAULT 'local'");
            db.execSQL("UPDATE users SET provider = 'auth0' WHERE password = 'sso-auth0'");
            db.execSQL("UPDATE users SET provider = 'local' WHERE provider NOT IN ('local','auth0')");
            // Vider les mots de passe non conformes au format de hash (historiques en clair).
            db.execSQL("UPDATE users SET password = '' WHERE provider = 'local' AND password NOT LIKE 'pbkdf2-sha256$%'");
            // Le compte semé « admin » avec mot de passe en clair est neutralisé.
            db.execSQL("UPDATE users SET role = 'USER' WHERE username = 'admin' AND password = '' AND role = 'ADMIN'");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_users_authSubject ON users(authSubject)");
        }
    };

    public static synchronized AppDatabase getInstance(Context context) {
        if (instance == null) {
            instance = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "stock_database")
                    .addMigrations(MIGRATION_22_23)
                    // Repli destructif conservé uniquement pour les versions antérieures
                    // à 22 (schémas non exportés historiquement) ; toute migration à
                    // partir de la v22 est explicite (TM-13 : ne plus jamais perdre les
                    // données de stock / audit sur une montée de version).
                    .fallbackToDestructiveMigrationFrom(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
                    .build();
        }
        return instance;
    }
}
