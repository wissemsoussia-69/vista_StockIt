package com.example.stockit.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public final class LocaleHelper {

    private static final String PREFS = "stockit_locale";
    private static final String KEY_TAG = "language_tag";
    public static final String[] SUPPORTED_TAGS = {"fr", "en", "ar"};
    public static final String[] SUPPORTED_LABELS = {"French", "English", "Arabic"};

    private LocaleHelper() {}

    public static void apply(Context ctx, String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TAG, languageTag).apply();
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag));
    }

    public static void restore(Context ctx) {
        String tag = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAG, null);
        if (tag != null && !tag.isEmpty()) {
            LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
            String currentTag = current.isEmpty() ? "" : current.get(0).getLanguage();
            if (!tag.equals(currentTag)) {
                AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tag));
            }
        }
    }

    public static String currentTag(Context ctx) {
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (!current.isEmpty()) return current.get(0).getLanguage();
        String saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAG, null);
        return saved != null ? saved : "en";
    }

    public static int currentIndex(Context ctx) {
        String tag = currentTag(ctx);
        for (int i = 0; i < SUPPORTED_TAGS.length; i++) {
            if (SUPPORTED_TAGS[i].equals(tag)) return i;
        }
        return 0;
    }
}
