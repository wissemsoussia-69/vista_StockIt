package com.example.stockit.util;

/**
 * Échappement des cellules CSV (export inventaire, rapport quotidien).
 *
 * Une cellule sûre pour Excel / Sheets (TM-34) :
 *  - toujours entre guillemets, guillemets doublés (RFC 4180) ;
 *  - préfixe « ' » devant les formules potentielles (=, +, -, @, tabulation,
 *    retour chariot) — l'apostrophe est comprise comme texte par les tableurs.
 */
public final class Csv {

    private Csv() {}

    /** Cellule CSV sûre à ouvrir dans Excel / Sheets. */
    public static String cell(String s) {
        if (s == null) return "\"\"";
        String v = s.replace("\"", "\"\"");
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v + "\"";
    }
}
