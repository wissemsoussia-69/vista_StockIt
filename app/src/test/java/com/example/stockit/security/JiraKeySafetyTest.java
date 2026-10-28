package com.example.stockit.security;

import com.example.stockit.util.JiraPaths;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TM-42 / TM-18 — clés de tickets Jira : validation stricte avant tout appel REST,
 * encodage en pourcentage dans le chemin (jamais « ../../ » ni « ? » injectables).
 */
public class JiraKeySafetyTest {

    @Test
    public void traversalAndQueryStringsAreRejected() {
        for (String bad : new String[]{"../../rest/api/3/myself", "SD-1?expand=all", "SD-1/comment",
                "SD 1", "1-SD", "SD-", "", null, "SD--12", "sd-12 x"}) {
            assertFalse("doit être refusé : " + bad, JiraPaths.isValidKey(bad));
            assertNull(JiraPaths.normalizeKey(bad));
        }
    }

    @Test
    public void validKeysAreAcceptedAndNormalized() {
        assertTrue(JiraPaths.isValidKey("SD-12"));
        assertTrue(JiraPaths.isValidKey("ETXTUN-123456789"));
        assertEquals("SD-12", JiraPaths.normalizeKey("sd-12"));
        assertEquals("SD-12", JiraPaths.normalizeKey(" SD-12 "));
    }

    @Test
    public void keysArePercentEncodedForUrlPaths() {
        // Une clé valide ne contient que [A-Z0-9_-] : l'encodage doit laisser les
        // séparateurs intacts mais ne jamais laisser passer un '/' non encodé.
        assertEquals("SD-12", JiraPaths.encodeKey("SD-12"));
        // Les valeurs qui ne sont PAS des clés valides ne doivent jamais atteindre une URL :
        String rogue = "../../rest/api/3/myself";
        assertFalse(JiraPaths.isValidKey(rogue));
        String encoded = JiraPaths.encodeKey(rogue);
        assertFalse("aucun '/' ne doit rester traversable : " + encoded,
                encoded.replace("%2F", "").replace("%2f", "").contains("/"));
    }
}
