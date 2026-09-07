package com.example.stockit.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.stockit.BuildConfig;
import com.example.stockit.model.AnalyticsEvent;
import com.example.stockit.model.AppDatabase;
import com.example.stockit.model.AnalyticsEventDao;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnalyticsSyncWorker extends Worker {

    private static final String TAG = "AnalyticsSync";
    public  static final String WORK_NAME = "stockit.analytics_sync";

    private static final int MAX_BATCH_SIZE = 100;

    private static final int TTL_DAYS = 30;

    public AnalyticsSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                AnalyticsSyncWorker.class,
                6, TimeUnit.HOURS,
                1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        req);
        Log.i(TAG, "analytics sync worker planifie (period=6h, flex=1h)");
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        AnalyticsEventDao dao = AppDatabase.getInstance(ctx).analyticsEventDao();

        try {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TTL_DAYS);
            int purged = dao.purgeSyncedOlderThan(cutoff);
            if (purged > 0) Log.i(TAG, "purged " + purged + " old synced events");
        } catch (Throwable t) {
            Log.w(TAG, "purge failed (non fatal): " + t.getMessage());
        }

        final List<AnalyticsEvent> batch;
        try {
            batch = dao.peekUnsynced(MAX_BATCH_SIZE);
        } catch (Throwable t) {
            Log.e(TAG, "peekUnsynced KO", t);
            return Result.retry();
        }
        if (batch == null || batch.isEmpty()) {
            Log.i(TAG, "no events to sync - done");
            return Result.success();
        }

        final String payload = buildBatchJson(ctx, batch);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);

        StockItReporter.sendReportToUrl(
                BuildConfig.STOCKIT_ANALYTICS_WEBHOOK_URL,
                safeUser(ctx),
            "wissem.soussia@vista.com",
                "ANALYTICS_BATCH",
                payload,
                (success, bodyOrError) -> {
                    ok.set(success);
                    if (!success) Log.w(TAG, "batch upload KO: " + bodyOrError);
                    done.countDown();
                });

        try {
            if (!done.await(45, TimeUnit.SECONDS)) {
                Log.w(TAG, "webhook timeout - will retry");
                return Result.retry();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        if (!ok.get()) return Result.retry();

        try {
            List<Long> ids = new ArrayList<>(batch.size());
            for (AnalyticsEvent e : batch) ids.add(e.getId());
            dao.markSynced(ids);
            Log.i(TAG, "synced " + ids.size() + " events -> n8n");
        } catch (Throwable t) {
            Log.e(TAG, "markSynced KO", t);
        }

        return batch.size() >= MAX_BATCH_SIZE ? Result.retry() : Result.success();
    }


    private static String buildBatchJson(Context ctx, List<AnalyticsEvent> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 96);
        sb.append('{');
        sb.append("\"batch_version\":\"v1\",");
        sb.append("\"app_version\":").append(AnalyticsHelper.jsonQuote(BuildConfig.VERSION_NAME)).append(',');
        sb.append("\"os\":\"android-").append(Build.VERSION.SDK_INT).append("\",");
        sb.append("\"device\":").append(AnalyticsHelper.jsonQuote(Build.MODEL == null ? "" : Build.MODEL)).append(',');
        sb.append("\"batch_size\":").append(batch.size()).append(',');
        sb.append("\"events\":[");
        for (int i = 0; i < batch.size(); i++) {
            AnalyticsEvent e = batch.get(i);
            if (i > 0) sb.append(',');
            sb.append('{');
            sb.append("\"id\":").append(e.getId()).append(',');
            sb.append("\"name\":").append(AnalyticsHelper.jsonQuote(e.getEventName())).append(',');
            sb.append("\"user\":").append(AnalyticsHelper.jsonQuote(e.getUserId())).append(',');
            sb.append("\"ts\":").append(e.getTimestamp()).append(',');
            String p = e.getProperties();
            sb.append("\"props\":").append(p == null || p.isEmpty() ? "{}" : p);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String safeUser(Context ctx) {
        try {
            String u = SessionManager.get(ctx).getUsername();
            return (u == null || u.isEmpty()) ? "stockit-system" : u;
        } catch (Throwable ignored) {
            return "stockit-system";
        }
    }
}
