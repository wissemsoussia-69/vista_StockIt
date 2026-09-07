package com.example.stockit.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CrashReporter {

    private static final String TAG = "CrashReporter";
    private static volatile boolean installed = false;

    private CrashReporter() {}

    public static synchronized void install(@NonNull Context context) {
        if (installed) return;
        final Context appCtx = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                persistCrash(appCtx, thread, throwable);
            } catch (Throwable t) {
                Log.e(TAG, "persistCrash secondary failure (ignored)", t);
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });

        installed = true;
        Log.i(TAG, "uncaught exception handler installed");
    }

    private static void persistCrash(Context ctx, Thread thread, Throwable t) {
        StringWriter sw = new StringWriter(2048);
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        String trace = sw.toString();
        if (trace.length() > 4000) trace = trace.substring(0, 4000) + "...(truncated)";

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("exception", t.getClass().getName());
        String msg = t.getMessage();
        props.put("message", msg == null ? "" : msg);
        props.put("thread", thread.getName());
        props.put("stack", trace);
        try {
            com.example.stockit.model.AppDatabase db =
                    com.example.stockit.model.AppDatabase.getInstance(ctx);
            String user = "anonymous";
            try {
                String u = SessionManager.get(ctx).getUsername();
                if (u != null && !u.isEmpty()) user = u;
            } catch (Throwable ignored) {}
            com.example.stockit.model.AnalyticsEvent ev =
                    new com.example.stockit.model.AnalyticsEvent(
                            "crash", user, AnalyticsHelper.toJson(props),
                            System.currentTimeMillis());
            db.analyticsEventDao().insert(ev);
            Log.w(TAG, "crash persisted - will be pushed at next sync");
        } catch (Throwable persistError) {
            Log.e(TAG, "cannot persist crash - falling back to logcat only", persistError);
        }
    }
}
