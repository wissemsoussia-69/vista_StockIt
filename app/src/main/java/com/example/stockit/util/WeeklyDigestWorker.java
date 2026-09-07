package com.example.stockit.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.stockit.model.AppDatabase;
import com.example.stockit.model.Product;
import com.example.stockit.model.StockMovement;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class WeeklyDigestWorker extends Worker {

    private static final String TAG = "WeeklyDigest";
    public  static final String WORK_NAME = "stockit.weekly_digest";
    private static final String DEFAULT_RECIPIENT = "wissem.soussia@vista.com";

    public WeeklyDigestWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                WeeklyDigestWorker.class,
                7, TimeUnit.DAYS,
                12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(computeDelayToNextMondayMorning(), TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        req);
        Log.i(TAG, "weekly digest scheduled - next execution in "
                + (computeDelayToNextMondayMorning() / 3_600_000L) + " h");
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            AppDatabase db = AppDatabase.getInstance(ctx);

            long weekStart = getStartOfCurrentWeek();
            String weekPrefix = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    .format(new Date(weekStart));

            int created = 0, assigned = 0;
            Map<String, Integer> brandCounts = new HashMap<>();
            List<StockMovement> movements = db.stockMovementDao().getAll();
            for (StockMovement m : movements) {
                if (!isThisWeek(m.getDate(), weekStart)) continue;
                int qty = Math.abs(m.getQuantity());
                if ("IN".equals(m.getType())) created += qty;
                else if ("OUT".equals(m.getType())) assigned += qty;
            }

            int pending = 0;
            List<Product> products = db.productDao().getAll();
            for (Product p : products) {
                if (p.getQuantity() > 0) pending += p.getQuantity();
                if (p.getBrand() != null && !p.getBrand().isEmpty()) {
                    brandCounts.merge(p.getBrand(), 1, Integer::sum);
                }
            }
            String topBrands = topN(brandCounts, 3);

                String subject = "Weekly digest - week of " + weekPrefix;
            String message =
                    "StockIT weekly report\n\n"
                        + "- New assets created : " + created + "\n"
                        + "- Assigned assets    : " + assigned + "\n"
                        + "- Pending stock      : " + pending + " units\n"
                        + "- Top scanned brands : " + (topBrands.isEmpty() ? "n/a" : topBrands) + "\n\n"
                        + "Automatic summary - StockIT | Vistaprint ETX Tunis.";

            StockItReporter.sendEvent(ctx, subject, message, DEFAULT_RECIPIENT);
            Log.i(TAG, "digest sent: IN=" + created + " OUT=" + assigned
                    + " pending=" + pending + " topBrands=" + topBrands);
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "digest failed", e);
            return Result.retry();
        }
    }


    private static long getStartOfCurrentWeek() {
        Calendar cal = Calendar.getInstance();
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static boolean isThisWeek(String movementDate, long weekStartMs) {
        if (movementDate == null || movementDate.isEmpty()) return false;
        try {
            Date d = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    .parse(movementDate);
            return d != null && d.getTime() >= weekStartMs;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long computeDelayToNextMondayMorning() {
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, 8);
        target.set(Calendar.MINUTE, 0);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        int day = target.get(Calendar.DAY_OF_WEEK);
        int daysUntilMonday = (Calendar.MONDAY - day + 7) % 7;
        if (daysUntilMonday == 0 && target.getTimeInMillis() < System.currentTimeMillis()) {
            daysUntilMonday = 7;
        }
        target.add(Calendar.DAY_OF_YEAR, daysUntilMonday);
        long delta = target.getTimeInMillis() - System.currentTimeMillis();
        return Math.max(delta, TimeUnit.MINUTES.toMillis(15));
    }

    private static String topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
