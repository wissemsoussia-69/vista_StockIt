package com.example.stockit.util;

import java.util.List;

/**
 * Rattachement d'une identité SSO Auth0 à une ligne Room « users » (TM-22).
 *
 * L'identité est le sujet Auth0 (« sub », immuable), jamais le nom affiché :
 *  - un sujet déjà lié retrouve SA ligne, même si le nom affiché a changé ;
 *  - un nom d'affichage identique ne permet jamais de récupérer la ligne d'un
 *    autre sujet, ni d'un compte local ;
 *  - une ancienne ligne SSO sans sujet (historique) est adoptée une seule fois
 *    (première connexion d'un employé du même nom), puis reste liée à son sujet.
 */
public final class SsoIdentity {

    /** Vue minimale d'une ligne « users » pour la décision (testable sans Room). */
    public interface Row {
        long getId();
        String getUsername();
        /** Sujet Auth0 déjà lié, null si inconnu (anciennes lignes SSO). */
        String getAuthSubject();
        /** "local" (mot de passe) ou "auth0" (SSO). */
        String getProvider();
    }

    private SsoIdentity() {}

    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_AUTH0 = "auth0";

    /**
     * Décision : id de la ligne à réutiliser, ou -1 pour créer un nouveau compte.
     *
     * @param users lignes existantes
     * @param authSubject sujet Auth0 (jamais null pour une vraie connexion SSO ;
     *                     null/ vide → jamais de reprise de ligne)
     * @param username nom affiché (peut être identique à un autre compte)
     */
    public static long resolve(List<Row> users, String authSubject, String username) {
        if (users == null) return -1;
        if (authSubject == null || authSubject.trim().isEmpty()) return -1; // pas d'identité fiable → création
        String subject = authSubject.trim();

        // 1) Le sujet est déjà lié : c'est SA ligne (même après un renommage).
        for (Row u : users) {
            if (subject.equals(u.getAuthSubject())) return u.getId();
        }

        // 2) Ancienne ligne SSO sans sujet (migration) : adoptée une fois.
        String name = username == null ? "" : username.trim();
        for (Row u : users) {
            if (!PROVIDER_AUTH0.equals(u.getProvider())) continue; // jamais un compte local
            if (u.getAuthSubject() != null && !u.getAuthSubject().isEmpty()) continue; // déjà liée à un autre
            if (!name.isEmpty() && name.equalsIgnoreCase(u.getUsername() == null ? "" : u.getUsername().trim())) {
                return u.getId();
            }
        }

        // 3) Aucune reprise : nouveau compte.
        return -1;
    }
}
