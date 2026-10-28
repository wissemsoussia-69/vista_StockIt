package com.example.stockit.security;

import com.example.stockit.util.Permissions;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TM-05 / TM-07 — matrice de permissions StockIT (décision alignée sur l'app iOS) :
 * USER = lecture seule ; TECHNICIEN = mouvements de stock, sorties ticket, réceptions,
 * expéditions ; MANAGER = + catalogue + journal d'audit ; ADMIN = + comptes + mode test.
 */
public class PermissionsTest {

    @Test
    public void userIsReadOnly() {
        String user = Permissions.USER;
        assertFalse(Permissions.canAdjustStock(user));
        assertFalse(Permissions.canEditStock(user));
        assertFalse(Permissions.canAdminister(user));
    }

    @Test
    public void technicianCanAdjustStockButNotEditCatalogueOrAdminister() {
        String tech = Permissions.TECHNICIEN;
        assertTrue(Permissions.canAdjustStock(tech));
        assertFalse(Permissions.canEditStock(tech));
        assertFalse(Permissions.canAdminister(tech));
    }

    @Test
    public void managerEditsCatalogueButCannotAdminister() {
        String manager = Permissions.MANAGER;
        assertTrue(Permissions.canAdjustStock(manager));
        assertTrue(Permissions.canEditStock(manager));
        assertFalse(Permissions.canAdminister(manager));
    }

    @Test
    public void adminHasFullAccess() {
        String admin = Permissions.ADMIN;
        assertTrue(Permissions.canAdjustStock(admin));
        assertTrue(Permissions.canEditStock(admin));
        assertTrue(Permissions.canAdminister(admin));
    }

    @Test
    public void unknownOrInjectedRolesNeverGainPrivileges() {
        // NB : les espaces extrêmes sont normalisées (trim + majuscules), comme Roles.normalize.
        for (String bad : new String[]{null, "", "SUPERADMIN", "admin; DROP TABLE users", "administrateur", "User"}) {
            assertFalse("canAdjustStock ne doit pas être accordé à " + bad, Permissions.canAdjustStock(bad));
            assertFalse(Permissions.canEditStock(bad));
            assertFalse(Permissions.canAdminister(bad));
        }
    }

    @Test
    public void normalizationIsStrict() {
        assertEquals(Permissions.USER, Permissions.normalize(null));
        assertEquals(Permissions.USER, Permissions.normalize("SUPERADMIN"));
        assertEquals(Permissions.TECHNICIEN, Permissions.normalize("technicien"));
        assertEquals(Permissions.ADMIN, Permissions.normalize("admin"));
    }

    private static void assertEquals(String expected, String actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
