package com.example.stockit.security;

import com.example.stockit.util.LoginGuard;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TM-08 — anti force-brute sur la connexion locale : 5 échecs → verrouillage temporaire.
 */
public class LoginGuardTest {

    private void fail(String name, int times) {
        for (int i = 0; i < times; i++) LoginGuard.recordFailure(name);
    }

    @Test
    public void fiveFailuresLockTheAccount() {
        String user = "bruteforce-user";
        fail(user, 5);
        assertTrue("5 échecs doivent verrouiller le compte", LoginGuard.isLockedOut(user));
    }

    @Test
    public void fourFailuresDoNotLock() {
        String user = "patient-user";
        fail(user, 4);
        assertFalse(LoginGuard.isLockedOut(user));
    }

    @Test
    public void successClearsTheFailures() {
        String user = "recovering-user";
        fail(user, 5);
        LoginGuard.clear(user);
        assertFalse("une réussite doit réinitialiser les échecs", LoginGuard.isLockedOut(user));
    }

    @Test
    public void lockIsPerAccount() {
        fail("victim", 5);
        assertFalse("un autre compte n'est pas verrouillé", LoginGuard.isLockedOut("other"));
    }
}
