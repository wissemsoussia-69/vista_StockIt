package com.example.stockit.security;

import com.example.stockit.util.DeliveryNoteParser;
import com.example.stockit.util.PackageLabelParser;
import com.example.stockit.util.TicketMatcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TM-18 / TM-35 / TM-41 — sortie des modèles IA (Java) : seuls les tickets envoyés
 * sont acceptés, quantités bornées, texte OCR plafonné, pas d'écho du texte du
 * modèle dans les erreurs.
 */
public class LlmOutputSafetyTest {

    // ------------------------------------------------------------------ TM-18 tickets

    @Test
    public void assignmentsAreLimitedToSentTicketIds() {
        Set<String> sent = new HashSet<>(Arrays.asList("SD-1"));
        java.util.List<TicketMatcher.Assignment> r =
                TicketMatcher.parseAssignments("[{\"ticketId\":\"SD-1\",\"qty\":2},{\"ticketId\":\"EVIL-666\",\"qty\":3}]",
                        5, sent);
        assertEquals("aucun ticket non envoyé ne doit être accepté", Arrays.asList("SD-1"), ids(r));
    }

    @Test
    public void assignmentsTotalIsCapped() {
        Set<String> sent = new HashSet<>(Arrays.asList("SD-1", "SD-2"));
        java.util.List<TicketMatcher.Assignment> r =
                TicketMatcher.parseAssignments("[{\"ticketId\":\"SD-1\",\"qty\":9000},{\"ticketId\":\"SD-2\",\"qty\":9000}]",
                        4, sent);
        int total = 0;
        for (TicketMatcher.Assignment a : r) total += a.qty;
        assertTrue("quantité totale plafonnée (était " + total + ")", total <= 4);
    }

    // ------------------------------------------------------------------ TM-35 quantités

    @Test
    public void labelQuantityIsBounded() {
        assertTrue("quantité 2147483647 doit être bornée à 1..500",
                PackageLabelParser.boundQuantity(2147483647) <= PackageLabelParser.MAX_QUANTITY);
        assertTrue(PackageLabelParser.boundQuantity(Integer.MAX_VALUE) >= 1);
        assertEquals(1, PackageLabelParser.boundQuantity(0));
        assertEquals(1, PackageLabelParser.boundQuantity(-5));
        assertEquals(20, PackageLabelParser.boundQuantity(20));
    }

    // ------------------------------------------------------------------ TM-35 OCR plafonné

    @Test
    public void ocrTextSentToTheModelIsCapped() {
        String big = "PO 1234 " + repeat('x', 200_000);
        assertTrue("le texte envoyé au modèle doit être plafonné (8 000 caractères)",
                DeliveryNoteParser.clipOcr(big).length() <= 8_000);
        assertTrue(PackageLabelParser.clipOcr(big).length() <= 8_000);
        assertEquals("abc", DeliveryNoteParser.clipOcr("abc"));
    }

    // ------------------------------------------------------------------ TM-41 erreurs

    @Test
    public void parserErrorsDoNotEchoModelOutput() {
        String rawLlm = "INTERNAL-PROMPT-LEAK this is not json";
        String err = PackageLabelParser.parseError(rawLlm);
        assertFalse("l'erreur ne doit pas contenir la sortie brute du modèle", err.contains("INTERNAL-PROMPT-LEAK"));
        assertFalse(DeliveryNoteParser.parseError(rawLlm).contains("INTERNAL-PROMPT-LEAK"));
    }

    // ------------------------------------------------------------------ helpers

    private static java.util.List<String> ids(java.util.List<TicketMatcher.Assignment> list) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (TicketMatcher.Assignment a : list) out.add(a.ticketId);
        return out;
    }

    private static String repeat(char c, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }
}
