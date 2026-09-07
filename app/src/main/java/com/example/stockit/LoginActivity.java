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
        loadProfiles();

        loginButton.setOnClickListener(v -> {
            String u = usernameEditText.getText().toString();
            String p = passwordEditText.getText().toString();
            if (!u.isEmpty() && !p.isEmpty()) performLocalLogin(u, p);
        });

        biometricButton.setOnClickListener(v -> {
            // Démo biométrique : profil admin local (fallback hors ligne).
            User admin = new User("admin", "admin123", "ADMIN");
            showBiometricPromptForUser(admin);
        });

        checkBiometricSupport(biometricButton);
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

        oktaAuth.signIn(this, new Auth0Manager.SignInCallback() {
            @Override
            public void onSuccess(String username, @Nullable String email, String role) {
                setLoading(false);
                // Persiste l'utilisateur SSO dans Room afin que MainController.currentUser
                // soit rempli et que les vérifications role-based (isAdmin, canEditStock)
                // fonctionnent normalement pendant toute la session.
                controller.loginSso(username, role, (success, dbUser) -> {
                    Intent i = new Intent(LoginActivity.this, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                    finish();
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
            if (users.size() < 5) { // Si l'équipe n'est pas là au complet
                android.util.Log.d("LoginActivity", "Team incomplete, triggering seed...");
                controller.login("admin", "admin123", (success, user) -> {
                    loadProfiles(); // On recharge pour voir tout le monde
                });
                return;
            }

            android.util.Log.d("LoginActivity", "Profiles loaded: " + users.size());
            profileRecyclerView.setLayoutManager(new GridLayoutManager(this, 3));
            profileRecyclerView.setAdapter(new ProfileAdapter(users, user -> {
                showBiometricPromptForUser(user);
            }));
        });
    }

    private void showBiometricPromptForUser(User user) {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                performLocalLogin(user.getUsername(), user.getPassword());
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(LoginActivity.this, "Erreur empreinte : " + errString, Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Connexion pour " + user.getUsername())
                .setSubtitle("Posez votre doigt pour valider votre identité")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void performLocalLogin(String u, String p) {
        setLoading(true);
        controller.login(u, p, (success, user) -> {
            setLoading(false);
            if (success) {
                SessionManager.get(this).saveLocalSession(
                        user != null ? user.getUsername() : u,
                        user != null ? user.getRole() : "USER");
                startActivity(new Intent(this, MainActivity.class));
                finish();
            } else {
                Toast.makeText(this, "Identifiants incorrects", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (loginButton != null) loginButton.setEnabled(!loading);
        if (oktaSignInButton != null) oktaSignInButton.setEnabled(!loading && oktaAuth.isConfigured());
    }

    private void checkBiometricSupport(Button biometricButton) {
        BiometricManager manager = BiometricManager.from(this);
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG | BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricButton.setVisibility(View.VISIBLE);
        }
    }
}
