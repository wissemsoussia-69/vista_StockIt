package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.controller.MainController;
import com.example.stockit.controller.ProfileAdapter;
import com.example.stockit.model.User;
import com.example.stockit.util.Auth0Manager;
import com.example.stockit.util.SessionManager;
import com.google.android.material.button.MaterialButton;
import java.util.concurrent.Executor;

/**
 * StockIT — Écran de connexion.
 *
 * Le parcours nominal passe par le SSO Okta (bouton principal en tête d'écran).
 * Le formulaire classique + biométrique est conservé UNIQUEMENT comme repli
 * de démonstration lorsque Okta n'est pas configuré ou en environnement hors ligne.
 */
public class LoginActivity extends AppCompatActivity {

    /** Action à exécuter après la connexion (ex. raccourci "add_product"). */
    public static final String EXTRA_THEN_ACTION = "then_action";

    private MainController controller;
    private Auth0Manager oktaAuth;
    private EditText usernameEditText;
    private EditText passwordEditText;
    private ProgressBar progressBar;
    private Button loginButton;
    private MaterialButton biometricButton;
    private MaterialButton oktaSignInButton;
    private TextView oktaStatusText;
    private RecyclerView profileRecyclerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Bascule du Splash Vista vers le thème normal juste avant l'inflation.
        setTheme(R.style.Theme_StockIT);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        controller = new MainController(this);
        oktaAuth = Auth0Manager.get(getApplicationContext());

        usernameEditText     = findViewById(R.id.usernameEditText);
        passwordEditText     = findViewById(R.id.passwordEditText);
        loginButton          = findViewById(R.id.loginButton);
        biometricButton      = findViewById(R.id.biometricButton);
        oktaSignInButton     = findViewById(R.id.oktaSignInButton);
        oktaStatusText       = findViewById(R.id.oktaStatusText);
        progressBar          = findViewById(R.id.loginProgressBar);
        profileRecyclerView  = findViewById(R.id.profileRecyclerView);

        setupOktaSso();

        // Le fallback local (profil picker + user/password + biométrie liée à un
        // profil Room) contourne Auth0 : on ne l'expose qu'en build debug pour
        // faciliter les démos hors ligne. En release, seul le SSO est disponible.
        if (BuildConfig.DEBUG) {
            enableLocalFallbackUi();
        } else {
            hideLocalFallbackUi();
        }
    }

    private void enableLocalFallbackUi() {
        // TM-08 : aucun compte par défaut. En Debug sans compte local, on provisionne
        // un compte « demo » avec un mot de passe aléatoire (affiché pré-rempli) pour
        // la démo hors ligne — plus aucune credential en dur dans le code.
        if (controller != null) {
            controller.getUsers(users -> {
                boolean hasLocal = false;
                for (com.example.stockit.model.User u : users) {
                    if (u.getProvider() == null || "local".equals(u.getProvider())) { hasLocal = true; break; }
                }
                if (!hasLocal) provisionDemoAccount();
                else loadProfiles();
            });
        }

        loginButton.setOnClickListener(v -> {
            String u = usernameEditText.getText().toString();
            String p = passwordEditText.getText().toString();
            if (!u.isEmpty() && !p.isEmpty()) performLocalLogin(u, p);
        });

        biometricButton.setOnClickListener(v -> {
            // Démo biométrique : premier compte local (fallback hors ligne).
            controller.getUsers(users -> {
                com.example.stockit.model.User local = null;
                for (com.example.stockit.model.User u : users) {
                    if (u.getProvider() == null || "local".equals(u.getProvider())) { local = u; break; }
                }
                if (local != null) showBiometricPromptForUser(local);
            });
        });

        checkBiometricSupport(biometricButton);
    }

    /** Provisionne le compte de démo Debug (mot de passe aléatoire, pré-rempli). */
    private void provisionDemoAccount() {
        String password = com.example.stockit.util.LoginGuard.class.getSimpleName()
                + "-" + new java.security.SecureRandom().ints(8, 'a', 'z')
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
        controller.provisionLocalUser("demo", password, "MANAGER", (ok, user) -> {
            if (ok) {
                if (usernameEditText != null) usernameEditText.setText("demo");
                if (passwordEditText != null) passwordEditText.setText(password);
                loadProfiles();
            } else {
                loadProfiles();
            }
        });
    }

    private void hideLocalFallbackUi() {
        View profileTitle = findViewById(R.id.profileSectionTitle);
        View fallbackHint = findViewById(R.id.localFallbackHint);
        if (profileTitle != null)       profileTitle.setVisibility(View.GONE);
        if (profileRecyclerView != null) profileRecyclerView.setVisibility(View.GONE);
        if (fallbackHint != null)       fallbackHint.setVisibility(View.GONE);
        if (usernameEditText != null)   usernameEditText.setVisibility(View.GONE);
        if (passwordEditText != null)   passwordEditText.setVisibility(View.GONE);
        if (loginButton != null)        loginButton.setVisibility(View.GONE);
        if (biometricButton != null)    biometricButton.setVisibility(View.GONE);
    }

    // -----------------------------------------------------------------
    // Auth0 SSO (Vista)
    // -----------------------------------------------------------------

    private void setupOktaSso() {
        if (!oktaAuth.isConfigured()) {
            oktaSignInButton.setEnabled(false);
            oktaStatusText.setVisibility(View.VISIBLE);
            oktaStatusText.setText(R.string.sso_not_configured);
        }

        oktaSignInButton.setOnClickListener(v -> startOktaSignIn());
    }

    private void startOktaSignIn() {
        setLoading(true);
        oktaStatusText.setVisibility(View.GONE);

        // Vrai pendant l'ouverture de la page Okta (l'app passe brièvement en arrière-plan) — iOS signingIn.
        com.example.stockit.util.AppSession.allowExternal();
        oktaAuth.signIn(this, new Auth0Manager.SignInCallback() {
            @Override
            public void onSuccess(String username, @Nullable String email, String role, @Nullable String subject) {
                setLoading(false);
                // Persiste l'utilisateur SSO dans Room (clé = sujet Auth0 immuable) afin que
                // MainController.currentUser soit rempli et que les vérifications role-based
                // (isAdmin, canEditStock) fonctionnent normalement pendant toute la session.
                controller.loginSso(subject, username, role, (success, dbUser) -> {
                    if (!success) {
                        oktaStatusText.setVisibility(View.VISIBLE);
                        oktaStatusText.setText(getString(R.string.sso_error, L("base locale indisponible")));
                        return;
                    }
                    openMain();
                });
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                oktaStatusText.setVisibility(View.VISIBLE);
                oktaStatusText.setText(getString(R.string.sso_error, message));
            }
        });
    }

    // -----------------------------------------------------------------
    // Fallback local (démo hors ligne)
    // -----------------------------------------------------------------

    private void loadProfiles() {
        controller.getUsers(users -> {
            // TM-08 : plus de compte semé — la liste reflète les comptes réels
            // (locaux provisionnés en Debug, comptes SSO existants).
            android.util.Log.d("LoginActivity", "Profiles loaded: " + users.size());
            profileRecyclerView.setLayoutManager(new GridLayoutManager(this, 3));
            profileRecyclerView.setAdapter(new ProfileAdapter(users, user -> {
                // La biométrie ne débloque un compte QUE s'il existe déjà ; le
                // mot de passe reste vérifié par performLocalLogin.
                if ("local".equals(user.getProvider() == null ? "local" : user.getProvider())) {
                    showBiometricPromptForUser(user);
                } else {
                    Toast.makeText(this, L("Compte SSO : connectez-vous via Okta"), Toast.LENGTH_SHORT).show();
                }
            }));
        });
    }

    private void showBiometricPromptForUser(User user) {
        int can = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            // Aucun verrouillage configuré sur l'appareil : la biométrie ne peut pas
            // valider l'identité — le mot de passe (saisi) reste obligatoire.
            Toast.makeText(this, L("Verrouillez l'appareil pour utiliser la biométrie, ou saisissez le mot de passe"), Toast.LENGTH_LONG).show();
            return;
        }
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // La biométrie a validé l'identité (écran de verrouillage de l'appareil) :
                // la session est ouverte pour ce compte local — le mot de passe haché
                // n'est jamais utilisé ni exposé.
                openLocalSession(user);
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(LoginActivity.this, L("Erreur empreinte : %@", errString), Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(L("Connexion pour %@", user.getUsername()))
                .setSubtitle(L("Posez votre doigt pour valider votre identité"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    /** Ouvre la session locale sans mot de passe (identité validée par la biométrie). */
    private void openLocalSession(com.example.stockit.model.User user) {
        SessionManager.get(this).saveLocalSession(user.getUsername(), user.getRole());
        com.example.stockit.controller.MainController.setSessionUser(user);
        openMain();
    }

    private void performLocalLogin(String u, String p) {
        setLoading(true);
        controller.login(u, p, (success, user) -> {
            setLoading(false);
            if (success) {
                SessionManager.get(this).saveLocalSession(
                        user != null ? user.getUsername() : u,
                        user != null ? user.getRole() : "USER");
                openMain();
            } else {
                Toast.makeText(this, L("Identifiants incorrects"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openMain() {
        Intent i = new Intent(this, MainActivity.class);
        String then = getIntent() != null ? getIntent().getStringExtra(EXTRA_THEN_ACTION) : null;
        if (then != null) i.setAction(then);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loginButton != null) loginButton.setEnabled(!loading);
        if (oktaSignInButton != null) oktaSignInButton.setEnabled(!loading && oktaAuth.isConfigured());
    }

    private static String L(String key, Object... args) { return com.example.stockit.util.AppLanguage.L(key, args); }

    private void checkBiometricSupport(Button biometricButton) {
        BiometricManager manager = BiometricManager.from(this);
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricButton.setVisibility(View.VISIBLE);
        }
    }
}
