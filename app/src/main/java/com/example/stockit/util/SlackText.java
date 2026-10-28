package com.example.stockit.util;

/**
 * Échappement mrkdwn Slack des textes issus de l'app (noms de produits, motifs de
 * tickets, justifications du modèle) — TM-19 : plus aucune mention de canal
 * (&lt;!channel&gt;), mention d'utilisateur (&lt;@U…&gt;) ni lien forgé.
 */
public final class SlackText {

    private SlackText() {}

    /** Texte sûr à poster sur Slack : &amp; &lt; &gt; neutralisés (mrkdwn devient texte). */
    public static String escape(String s) {
        if (s == null) return null;
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
