package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.controller.MainController;
import com.example.stockit.controller.NotificationHelper;
import com.example.stockit.controller.ProfileAdapter;
import com.example.stockit.model.User;
import com.example.stockit.util.Auth0Manager;
import com.example.stockit.util.SessionManager;
import com.google.android.material.button.MaterialButton;
import java.util.Locale;
import java.util.concurrent.Executor;

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
    private final java.util.Random random = new java.util.Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StockIT);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        controller = MainController.getInstance(this);
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

        runOktaSignIn();
    }

    private void runOktaSignIn() {
        setLoading(true);
        oktaAuth.signIn(this, new Auth0Manager.SignInCallback() {
            @Override
            public void onSuccess(String username, @Nullable String email, String role) {
                setLoading(false);
                completeeSsoLogin(username, role);
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                oktaStatusText.setVisibility(View.VISIBLE);
                oktaStatusText.setText(getString(R.string.sso_error, message));
            }
        });
    }

    private void startPostSsoVerification(String username, @Nullable String email, String role) {
        final int otp = 100000 + random.nextInt(900000);
        showPostSsoMethodDialog(username, email, role, otp);
    }

    private void showPostSsoMethodDialog(String username, @Nullable String email, String role, int otp) {
        String[] methods = {
                getString(R.string.sso_verify_method_notification),
                getString(R.string.sso_verify_method_secret)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.sso_verify_title)
                .setMessage(R.string.sso_verify_subtitle)
                .setCancelable(false)
                .setItems(methods, (d, which) -> {
                    if (which == 0) {
                        launchNotificationOtpVerification(username, email, role, otp);
                    } else {
                        launchSecretCodeVerification(username, email, role);
                    }
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    oktaStatusText.setVisibility(View.VISIBLE);
                    oktaStatusText.setText(R.string.sso_verify_cancelled);
                })
                .show();
    }

    private void launchNotificationOtpVerification(String username, @Nullable String email, String role, int otp) {
        String msg = getString(R.string.sso_verify_notification_message, otp);
        NotificationHelper.showNotification(this,
                getString(R.string.sso_verify_notification_title),
                msg,
                (int) (System.currentTimeMillis() % Integer.MAX_VALUE));
        showCodePrompt(
                getString(R.string.sso_verify_otp_prompt_title),
                getString(R.string.sso_verify_otp_prompt_hint),
                input -> {
                    String expected = String.format(Locale.US, "%06d", otp);
                    return expected.equals(input.trim());
                },
                () -> completeeSsoLogin(username, role));
    }

    private void launchSecretCodeVerification(String username, @Nullable String email, String role) {
        final String expected = BuildConfig.SSO_SECRET_CODE == null ? "" : BuildConfig.SSO_SECRET_CODE.trim();
        if (expected.isEmpty()) {
            oktaStatusText.setVisibility(View.VISIBLE);
            oktaStatusText.setText(R.string.sso_verify_secret_not_configured);
            return;
        }
        showCodePrompt(
                getString(R.string.sso_verify_secret_prompt_title),
                getString(R.string.sso_verify_secret_prompt_hint),
                input -> expected.equals(input.trim()),
                () -> completeeSsoLogin(username, role));
    }

    private interface InputValidator {
        boolean isValid(String input);
    }

    private void showCodePrompt(String title,
                                String hint,
                                InputValidator validator,
                                Runnable onVerified) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(hint);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton(R.string.action_verify, (d, w) -> {
                    String val = input.getText() == null ? "" : input.getText().toString();
                    if (!validator.isValid(val)) {
                        oktaStatusText.setVisibility(View.VISIBLE);
                        oktaStatusText.setText(R.string.sso_verify_invalid_code);
                        Toast.makeText(this, R.string.sso_verify_invalid_code, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    oktaStatusText.setVisibility(View.GONE);
                    onVerified.run();
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    oktaStatusText.setVisibility(View.VISIBLE);
                    oktaStatusText.setText(R.string.sso_verify_cancelled);
                })
                .show();
    }

    private void completeeSsoLogin(String username, String role) {
        com.example.stockit.util.AnalyticsHelper.logLogin(
                LoginActivity.this, "sso", role);
        controller.loginSso(username, role, (success, dbUser) -> {
            Intent i = new Intent(LoginActivity.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
    }


    private void loadProfiles() {
        controller.getUsers(users -> {
            if (users.size() < 5) { // If the team seed is incompletee
                android.util.Log.d("LoginActivity", "Team incompletee, triggering seed...");
                controller.login("admin", "admin123", (success, user) -> {
                    loadProfiles(); // Reload to display the completee team
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
                com.example.stockit.util.AnalyticsHelper.logLogin(
                        LoginActivity.this, "biometric",
                        user != null ? user.getRole() : null);
                performLocalLogin(user.getUsername(), user.getPassword());
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(LoginActivity.this, getString(R.string.toast_biometric_error, errString), Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.dlg_title_login_for, user.getUsername()))
            .setSubtitle("Place your finger to verify your identity")
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
                Toast.makeText(this, R.string.toast_wrong_credentials, Toast.LENGTH_SHORT).show();
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
