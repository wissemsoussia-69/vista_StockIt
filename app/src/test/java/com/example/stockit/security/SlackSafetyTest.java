package com.example.stockit.security;

import com.example.stockit.util.SlackText;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TM-19 — injection mrkdwn Slack : un nom de produit ou un motif de ticket ne doit
 * pas pouvoir mentionner un canal entier (@channel) ou forger un lien.
 */
public class SlackSafetyTest {

    @Test
    public void channelMentionsAreEscaped() {
        String out = SlackText.escape("🚨 Produit <!channel> & <https://evil.example|cliquez>");
        assertFalse(out.contains("<!channel>"));
        assertFalse(out.contains("<https://evil.example"));
        assertTrue(out.contains("&lt;!channel&gt;"));
        assertTrue(out.contains("&amp;"));
    }

    @Test
    public void normalTextIsUnchanged() {
        assertEquals("Rapport de stock : 12 articles", SlackText.escape("Rapport de stock : 12 articles"));
        assertEquals("", SlackText.escape(""));
        assertEquals(null, SlackText.escape(null));
    }

    @Test
    public void userNamesCannotForgeMentions() {
        // <@U12345> = mention directe d'un utilisateur.
        String out = SlackText.escape("Sortie pour <@U999>");
        assertFalse(out.contains("<@U"));
    }

    /**
     * TM-10 — webhook Slack en HTTP refusé : le bot token ne doit jamais voyager
     * en clair (usable = HTTPS uniquement).
     */
    @Test
    public void slackWebhookMustUseHttps() {
        assertTrue("un webhook HTTPS est accepté",
                com.example.stockit.util.SlackNotifier.usable("https://hooks.slack.test/T/B/X"));
        assertFalse("un webhook HTTP doit être refusé (token en clair)",
                com.example.stockit.util.SlackNotifier.usable("http://hooks.slack.test/T/B/X"));
        assertFalse(com.example.stockit.util.SlackNotifier.usable(null));
        assertFalse(com.example.stockit.util.SlackNotifier.usable(""));
        assertFalse(com.example.stockit.util.SlackNotifier.usable("https://REPLACE-ME"));
    }
}
