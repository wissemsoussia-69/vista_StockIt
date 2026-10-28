package com.example.stockit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.stockit.controller.MainController;
import com.example.stockit.model.User;
import com.example.stockit.util.LoginGuard;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TM-08 / TM-22 (base Room, nécessite un émulateur ou un appareil) :
 * comptes locaux hachés PBKDF2, verrouillage anti force-brute, disparition de
 * la porte dérobée historique. Le compte de test est provisionné avec un mot
 * de passe aléatoire par exécution — AUCUNE credential de production n'est
 * utilisée (les tests UI iOS font de même, cf. SECURITY.md).
 *
 * Lancement : ./gradlew :app:connectedDebugAndroidTest (émulateur requis).
 */
@RunWith(AndroidJUnit4.class)
public class LocalAuthInstrumentedTest {

    private static final String RANDOM_PASSWORD = "Random-" + new java.security.SecureRandom().ints(12, 'a', 'z')
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();

    /** Exécute un appel contrôleur bloquant et rend l'utilisateur courant. */
    private static User login(MainController controller, String u, String p) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<User> out = new AtomicReference<>();
        controller.login(u, p, (success, user) -> {
            out.set(success ? user : null);
            latch.countDown();
        });
        assertTrue(latch.await(20, TimeUnit.SECONDS));
        return out.get();
    }

    private static void provision(MainController controller, String name, String role) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        controller.provisionLocalUser(name, RANDOM_PASSWORD, role, (success, user) -> {
            ok.set(success);
            latch.countDown();
        });
        assertTrue(latch.await(20, TimeUnit.SECONDS));
        assertTrue("provisionnement refusé (password=" + RANDOM_PASSWORD.length() + " caractères)", ok.get());
    }

    private static void assertTrue(boolean v) { org.junit.Assert.assertTrue(v); }
    private static void assertTrue(String msg, boolean v) { org.junit.Assert.assertTrue(msg, v); }

    @Test
    public void provisionedAccountLogsInWithTheRandomPassword() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MainController controller = new MainController(ctx);
        String name = "instr-tech";
        provision(controller, name, "TECHNICIEN");
        assertNotNull("le compte provisionné doit accepter son mot de passe aléatoire",
                login(controller, name, RANDOM_PASSWORD));
        assertNull("un mauvais mot de passe doit être refusé", login(controller, name, "not-the-password"));
    }

    @Test
    public void fiveFailuresLockTheAccount() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MainController controller = new MainController(ctx);
        String name = "instr-lock";
        provision(controller, name, "USER");
        LoginGuard.clear(name);
        for (int i = 0; i < 5; i++) {
            assertNull(login(controller, name, "wrong-" + i));
        }
        // Le bon mot de passe doit être refusé pendant le verrouillage (anti force-brute).
        assertNull("brute force : le mot de passe correct doit être refusé pendant le verrouillage",
                login(controller, name, RANDOM_PASSWORD));
    }

    @Test
    public void legacyAdminBackdoorIsGone() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MainController controller = new MainController(ctx);
        assertNull("aucun compte semé ne doit exister ; le mot de passe historique doit être refusé",
                login(controller, "admin", "admin123"));
    }
}
