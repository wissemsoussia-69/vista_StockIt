package com.example.stockit.security;

import com.example.stockit.util.Csv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TM-34 — Injection de formules CSV : les cellules exportées (inventaire, rapport
 * quotidien) doivent être neutres à l'ouverture dans Excel / Sheets.
 */
public class CsvSafetyTest {

    @Test
    public void formulaPrefixIsNeutralised() {
        String out = Csv.cell("=HYPERLINK(\"http://evil\",\"click\")");
        // la cellule (sans guillemets) ne doit pas démarrer par '='
        String inner = out.replace("\"", "");
        assertFalse(inner.startsWith("="));
        assertTrue("un apostrophe de neutralisation est attendu : " + out, out.contains("'="));
    }

    @Test
    public void allDangerousPrefixesAreNeutralised() {
        for (String dangerous : new String[]{"+SUM(A1)", "-1", "@SUM(A1)", "=cmd"}) {
            String out = Csv.cell(dangerous);
            String inner = out.replace("\"", "");
            assertFalse("préfixe non neutralisé : " + dangerous, inner.startsWith(String.valueOf(dangerous.charAt(0))));
        }
    }

    @Test
    public void normalCellsAreQuotedAndEscaped() {
        // RFC 4180 : toute cellule est entre guillemets, guillemets doublés (comme l'app iOS).
        assertEquals("\"Clavier\"", Csv.cell("Clavier"));
        assertEquals("\"Souris, sans fil\"", Csv.cell("Souris, sans fil"));
        assertEquals("\"l'écran \"\"20\"\" pouces\"", Csv.cell("l'écran \"20\" pouces"));
        assertEquals("\"\"", Csv.cell(null));
        assertEquals("\"\"", Csv.cell(""));
    }
}
