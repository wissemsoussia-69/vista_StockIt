package com.example.stockit.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Anti force-brute sur la connexion locale (TM-08) : 5 échecs dans les 5 dernières
 * minutes verrouillent le compte pour la fenêtre. Compteur en mémoire (les échecs
 * ne persistent pas — un redémarrage ne prolonge pas le verrouillage).
 */
public final class LoginGuard {

    public static final int MAX_FAILED_LOGINS = 5;
    public static final long LOCKOUT_WINDOW_MS = 5 * 60_000L;

    private static final Map<String, List<Long>> FAILURES = new HashMap<>();

    private LoginGuard() {}

    /** Enregistre un échec de connexion pour ce nom d'utilisateur. */
    public static void recordFailure(String username) {
        if (username == null || username.trim().isEmpty()) return;
        String key = username.trim().toLowerCase(Locale.ROOT);
        List<Long> l = FAILURES.computeIfAbsent(key, k -> new ArrayList<>());
        l.add(System.currentTimeMillis());
    }

    /** true si le compte est temporairement verrouillé (≥ 5 échecs dans la fenêtre). */
    public static boolean isLockedOut(String username) {
        if (username == null || username.trim().isEmpty()) return false;
        String key = username.trim().toLowerCase(Locale.ROOT);
        List<Long> l = FAILURES.get(key);
        if (l == null) return false;
        long now = System.currentTimeMillis();
        l.removeIf(t -> now - t > LOCKOUT_WINDOW_MS);
        return l.size() >= MAX_FAILED_LOGINS;
    }

    /** Réussite : les échecs sont oubliés. */
    public static void clear(String username) {
        if (username != null) FAILURES.remove(username.trim().toLowerCase(Locale.ROOT));
    }

    /** Oublie les échecs hors fenêtre ( appelé avant chaque vérification). */
    public static void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, List<Long>>> it = FAILURES.entrySet().iterator();
        while (it.hasNext()) {
            List<Long> l = it.next().getValue();
            l.removeIf(t -> now - t > LOCKOUT_WINDOW_MS);
            if (l.isEmpty()) it.remove();
        }
    }
}
