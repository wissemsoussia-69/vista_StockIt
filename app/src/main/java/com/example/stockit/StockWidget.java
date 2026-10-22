package com.example.stockit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.example.stockit.model.AppDatabase;
import com.example.stockit.model.ProductDao;

/** Widget StockIT : vrais chiffres (total / stock bas / rupture), comme le widget iOS. */
public class StockWidget extends AppWidgetProvider {

    private static final String ACTION_REFRESH = "com.example.stockit.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        update(context, mgr, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) refreshAll(context);
    }

    /** Rafraîchit toutes les instances (appelé après un changement de stock). */
    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, StockWidget.class));
        if (ids != null && ids.length > 0) update(context, mgr, ids);
    }

    private static void update(Context context, AppWidgetManager mgr, int[] ids) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            int total = 0, low = 0, out = 0;
            try {
                ProductDao dao = AppDatabase.getInstance(app).productDao();
                total = dao.getTotalQuantity();
                low = dao.getLowStockCount();
                out = dao.getOutOfStockCount();
            } catch (Throwable t) {
                android.util.Log.w("StockWidget", "lecture stock KO", t);
            }
            for (int id : ids) {
                RemoteViews v = new RemoteViews(app.getPackageName(), R.layout.stock_widget);
                v.setTextViewText(R.id.widgetStockText, String.valueOf(total));
                v.setTextViewText(R.id.widgetTitle, "STOCKIT · " + com.example.stockit.util.AppLanguage.L("%@ bas · %@ rupture", low, out));

                Intent refresh = new Intent(app, StockWidget.class).setAction(ACTION_REFRESH);
                v.setOnClickPendingIntent(R.id.widgetBtnRefresh, PendingIntent.getBroadcast(app, 0, refresh,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                Intent open = new Intent(app, SplashActivity.class);
                v.setOnClickPendingIntent(R.id.widgetStockText, PendingIntent.getActivity(app, 1, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                mgr.updateAppWidget(id, v);
            }
        }, "widget-update").start();
    }
}
