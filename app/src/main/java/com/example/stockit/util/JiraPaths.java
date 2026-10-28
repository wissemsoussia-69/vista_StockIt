package com.example.stockit.util;

import com.example.stockit.shared.security.JiraKeys;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Validation / encodage des clés de tickets Jira (« SD-1234 ») avant tout appel
 * REST (TM-42, TM-18) : regex stricte partagée avec l'app iOS, encodage en
 * pourcentage des segments de chemin.
 */
public final class JiraPaths {

    private JiraPaths() {}

    /** true si la chaîne est une clé Jira utilisable (« SD-12 », pas « ../../x »). */
    public static boolean isValidKey(String key) {
        return key != null && JiraKeys.INSTANCE.isValid(key.trim());
    }

    /** Clé normalisée (majuscules), ou null si ce n'est pas une clé Jira. */
    public static String normalizeKey(String key) {
        if (key == null) return null;
        return JiraKeys.INSTANCE.normalize(key);
    }

    /** Clé prête pour un segment de chemin REST (encodée en pourcentage). */
    public static String encodeKey(String key) {
        if (key == null) return "";
        try {
            return URLEncoder.encode(key.trim(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
