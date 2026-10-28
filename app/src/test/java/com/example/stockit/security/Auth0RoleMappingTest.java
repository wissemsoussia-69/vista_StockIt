package com.example.stockit.security;

import com.example.stockit.util.Auth0Manager;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * TM-06 — correspondance EXACTE des groupes/claims de rôle : plus aucune recherche
 * de sous-chaîne (« NOT_ADMIN », « biotech » ne doivent rien accorder).
 * Les groupes reconnus : stockit-admin, stockit-manager, stockit-technicien
 * (alias -tech / -technician) ; les noms nus ADMIN/MANAGER/TECHNICIEN uniquement
 * dans le claim namespacé https://vista.com/roles.
 */
public class Auth0RoleMappingTest {

    @Test
    public void exactGroupNamesGrantRoles() {
        assertEquals("ADMIN", Auth0Manager.matchRole(Arrays.asList("stockit-admin", "everyone")));
        assertEquals("MANAGER", Auth0Manager.matchRole(Arrays.asList("users", "stockit-manager")));
        assertEquals("TECHNICIEN", Auth0Manager.matchRole("stockit-technicien"));
        assertEquals("TECHNICIEN", Auth0Manager.matchRole("stockit-tech"));
        assertEquals("TECHNICIEN", Auth0Manager.matchRole("stockit-technician"));
    }

    @Test
    public void substringsNeverGrantAnything() {
        assertNull("'not-an-admin' ne doit rien accorder", Auth0Manager.matchRole("not-an-admin"));
        assertNull(Auth0Manager.matchRole("ADMINISTRATIVE_ASSISTANT"));
        assertNull("'biotech' ne doit pas donner TECHNICIEN", Auth0Manager.matchRole("biotech-team"));
        assertNull(Auth0Manager.matchRole("MANAGEMENT_TRAINEE"));
        assertNull(Auth0Manager.matchRole("sysadmin-readonly"));
        assertNull(Auth0Manager.matchRole("admin"));
        assertNull(Auth0Manager.matchRole("manager"));
        assertNull("le nom de rôle nu ne compte que dans le claim namespacé", Auth0Manager.matchRole("ADMIN"));
    }

    @Test
    public void highestPrivilegeWinsWhateverTheOrder() {
        assertEquals("ADMIN",
                Auth0Manager.matchRole(Arrays.asList("stockit-technicien", "stockit-admin")));
        assertEquals("MANAGER",
                Auth0Manager.matchRole(Arrays.asList("stockit-technicien", "stockit-manager")));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertNull(Auth0Manager.matchRole(null));
        assertNull(Auth0Manager.matchRole(new HashMap<String, Object>()));
        assertNull(Auth0Manager.matchRole(Arrays.asList()));
    }

    /** Vue des claims d'un profil Auth0 (matchRole accepte List/Map/String). */
    @Test
    public void mapClaimsAreScanned() {
        Map<String, Object> roles = new HashMap<>();
        roles.put("https://vista.com/roles", Arrays.asList("MANAGER"));
        assertEquals("MANAGER", Auth0Manager.matchRole(roles));
    }
}
