package com.example.stockit.util;

/**
 * Matrice de permissions StockIT (rôles : ADMIN, MANAGER, TECHNICIEN, USER).
 *
 * <p>Stub de transition : comportement actuel — tout utilisateur connecté peut
 * ajuster le stock ; catalogue et administration inchangés.</p>
 */
public final class Permissions {

    public static final String ADMIN = "ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String TECHNICIEN = "TECHNICIEN";
    public static final String USER = "USER";

    private Permissions() {}

    /** Rôle normalisé ; toute valeur inconnue est ramenée à USER (lecture seule). */
    public static String normalize(String role) {
        if (role == null) return USER;
        String r = role.trim().toUpperCase(java.util.Locale.ROOT);
        return r.equals(ADMIN) || r.equals(MANAGER) || r.equals(TECHNICIEN) ? r : USER;
    }

    /** Création / modification / suppression du catalogue : ADMIN ou MANAGER. */
    public static boolean canEditStock(String role) {
        String r = normalize(role);
        return r.equals(ADMIN) || r.equals(MANAGER);
    }

    /** Mouvements de stock (+/−, sorties sur ticket, réceptions, expéditions) : USER est en lecture seule. */
    public static boolean canAdjustStock(String role) {
        String r = normalize(role);
        return r.equals(ADMIN) || r.equals(MANAGER) || r.equals(TECHNICIEN);
    }

    /** Administration (comptes, mode test) : ADMIN uniquement. */
    public static boolean canAdminister(String role) {
        return normalize(role).equals(ADMIN);
    }
}
