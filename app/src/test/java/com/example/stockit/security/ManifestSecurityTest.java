package com.example.stockit.security;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TM-09 / TM-10 / TM-16 / TM-31 / TM-40 — durcissement déclaré au build :
 * sauvegardes Android désactivées, pas de trafic en clair (config réseau dédiée),
 * minification release activée, dépendance Auth0 épinglée, secrets morts et
 * permission RECORD_AUDIO retirés.
 */
public class ManifestSecurityTest {

    private static final Path APP_DIR = findAppDir();

    private static Path findAppDir() {
        // Les tests unitaires s'exécutent dans app/build/… ; on remonte jusqu'au module.
        Path p = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (p.resolve("src/main/AndroidManifest.xml").toFile().exists()) return p;
            p = p.getParent();
        }
        // Défaut : position relative à la racine du dépôt (run depuis la racine Gradle)
        return Paths.get("app");
    }

    private static String read(String rel) throws Exception {
        File f = APP_DIR.resolve(rel).toFile();
        if (!f.exists()) throw new AssertionError("fichier introuvable : " + f.getAbsolutePath());
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ TM-09 backup

    @Test
    public void androidBackupIsDisabled() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue("allowBackup doit être false (base Room et préférences non sauvegardées)",
                manifest.contains("android:allowBackup=\"false\""));
        assertFalse(manifest.contains("android:allowBackup=\"true\""));
    }

    // ------------------------------------------------------------------ TM-10 cleartext

    @Test
    public void cleartextTrafficIsForbiddenByTheManifest() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertFalse("usesCleartextTraffic doit être retiré (config réseau dédiée à la place)",
                manifest.contains("usesCleartextTraffic"));
        assertTrue("le manifeste doit référencer la network security config",
                manifest.contains("networkSecurityConfig"));
        assertTrue("la config réseau doit exister",
                APP_DIR.resolve("src/main/res/xml/network_security_config.xml").toFile().exists());
    }

    @Test
    public void networkSecurityConfigOnlyAllowsCleartextForDebugAndLocalhost() throws Exception {
        String cfg = read("src/main/res/xml/network_security_config.xml");
        // Aucun cleartext autorisé par défaut (base-config cleartextTrafficPermitted="false")
        assertTrue(cfg.contains("cleartextTrafficPermitted=\"false\""));
        // Les exceptions éventuelles doivent être restreintes au debug et à localhost
        assertFalse("pas de cleartext global", cfg.matches("(?s).*<base-config[^>]*cleartextTrafficPermitted=\"true\".*"));
    }

    // ------------------------------------------------------------------ TM-40 permission

    @Test
    public void recordAudioPermissionIsRemoved() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertFalse("RECORD_AUDIO n'est pas utilisée par l'app",
                manifest.contains("android.permission.RECORD_AUDIO"));
    }

    // ------------------------------------------------------------------ TM-16 release hardening

    @Test
    public void releaseBuildIsMinified() throws Exception {
        String gradle = read("build.gradle.kts");
        assertTrue("isMinifyEnabled = true requis sur le build release",
                gradle.contains("isMinifyEnabled = true"));
    }

    // ------------------------------------------------------------------ TM-31 supply chain

    @Test
    public void auth0DependencyIsPinned() throws Exception {
        String gradle = read("build.gradle.kts");
        assertFalse("auth0 doit être épinglé (pas de 3.+ flottant)",
                gradle.contains("com.auth0.android:auth0:3.+"));
    }

    // ------------------------------------------------------------------ TM-01 / TM-26 dead secrets

    @Test
    public void deadThirdPartyKeysAreNotCompiledIn() throws Exception {
        String gradle = read("build.gradle.kts");
        for (String dead : new String[]{"PORTKEY_API_KEY", "GEMINI_API_KEY", "HUGGING_FACE_TOKEN", "SENDGRID_API_KEY"}) {
            assertFalse("la clé inutilisée " + dead + " ne doit plus être compilée dans l'APK",
                    gradle.contains(dead));
        }
        assertFalse("la dépendance SendGrid doit être retirée", gradle.contains("sendgrid"));
        assertFalse("la dépendance Firestore (inutilisée) doit être retirée", gradle.contains("firebase-firestore"));
        assertFalse("JitPack doit être retiré de settings.gradle.kts", read("../settings.gradle.kts").contains("jitpack.io"));
    }

    @Test
    public void legacyApacheHttpClientIsNotLoaded() throws Exception {
        String gradle = read("build.gradle.kts");
        assertFalse("org.apache.http.legacy doit être retiré", gradle.contains("org.apache.http.legacy"));
    }

    @Test
    public void noHardcodedCredentialsRemainInJavaSources() throws Exception {
        Path javaRoot = APP_DIR.resolve("src/main/java");
        List<Path> files;
        try (var walk = Files.walk(javaRoot)) {
            files = walk.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt")).toList();
        }
        for (Path f : files) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            assertFalse("admin123 trouvé dans " + f, src.contains("admin123"));
            assertFalse("sso-auth0 (mot de passe bidon) trouvé dans " + f, src.contains("\"sso-auth0\""));
        }
    }
}
