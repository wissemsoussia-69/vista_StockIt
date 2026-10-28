package com.example.stockit.security;

import com.example.stockit.util.SsoIdentity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * TM-22 — usurpation d'identité SSO : le rattachement doit être clé sur le sujet
 * Auth0 (immuable), jamais sur le nom affiché.
 */
public class SsoIdentityTest {

    private static class Row implements SsoIdentity.Row {
        final long id;
        final String username;
        final String subject;
        final String provider;

        Row(long id, String username, String subject, String provider) {
            this.id = id;
            this.username = username;
            this.subject = subject;
            this.provider = provider;
        }

        @Override public long getId() { return id; }
        @Override public String getUsername() { return username; }
        @Override public String getAuthSubject() { return subject; }
        @Override public String getProvider() { return provider; }
    }

    @Test
    public void ssoLoginMustNotAdoptALocalAccountWithTheSameName() {
        List<SsoIdentity.Row> users = new ArrayList<>();
        users.add(new Row(7, "admin", null, SsoIdentity.PROVIDER_LOCAL));
        long match = SsoIdentity.resolve(users, "auth0|someone-else", "admin");
        assertTrue("un compte local ne doit jamais être repris par une identité SSO",
                match != 7);
    }

    @Test
    public void sameDisplayNameDifferentSubjectsAreDifferentAccounts() {
        List<SsoIdentity.Row> users = new ArrayList<>();
        users.add(new Row(1, "Sam", "auth0|1111", SsoIdentity.PROVIDER_AUTH0));
        long match = SsoIdentity.resolve(users, "auth0|2222", "Sam");
        assertTrue("deux employés homonymes = deux comptes", match != 1);
    }

    @Test
    public void sameSubjectKeepsItsAccountEvenAfterRename() {
        List<SsoIdentity.Row> users = new ArrayList<>();
        users.add(new Row(1, "Sam", "auth0|1111", SsoIdentity.PROVIDER_AUTH0));
        long match = SsoIdentity.resolve(users, "auth0|1111", "Samuel Renamed");
        assertTrue("le sujet prime sur le nom affiché", match == 1);
    }

    @Test
    public void legacySsoRowWithoutSubjectIsAdoptedOnce() {
        List<SsoIdentity.Row> users = new ArrayList<>();
        users.add(new Row(3, "alice", null, SsoIdentity.PROVIDER_AUTH0));
        // première connexion SSO : la ligne historique (sans sujet) est liée
        assertTrue(SsoIdentity.resolve(users, "auth0|alice-sub", "alice") == 3);
        // deuxième identité avec le même nom : plus jamais adoptée
        List<SsoIdentity.Row> bound = new ArrayList<>();
        bound.add(new Row(3, "alice", "auth0|alice-sub", SsoIdentity.PROVIDER_AUTH0));
        long impostor = SsoIdentity.resolve(bound, "auth0|another", "alice");
        assertNotEquals("une ligne déjà liée à un sujet n'est pas réadoptée", 3, impostor);
    }

    @Test
    public void nullSubjectNeverAdoptsAnything() {
        List<SsoIdentity.Row> users = new ArrayList<>();
        users.add(new Row(7, "admin", null, SsoIdentity.PROVIDER_LOCAL));
        assertTrue(SsoIdentity.resolve(users, null, "admin") != 7);
        assertTrue(SsoIdentity.resolve(users, "", "admin") != 7);
    }
}
