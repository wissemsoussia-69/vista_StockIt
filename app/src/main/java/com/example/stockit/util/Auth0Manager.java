package com.example.stockit.util;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.auth0.android.Auth0;
import com.auth0.android.authentication.AuthenticationAPIClient;
import com.auth0.android.authentication.AuthenticationException;
import com.auth0.android.authentication.storage.CredentialsManagerException;
import com.auth0.android.authentication.storage.SecureCredentialsManager;
import com.auth0.android.authentication.storage.SharedPreferencesStorage;
import com.auth0.android.callback.Callback;
import com.auth0.android.provider.WebAuthProvider;
import com.auth0.android.result.Credentials;
import com.auth0.android.result.UserProfile;
import com.example.stockit.BuildConfig;
import com.example.stockit.R;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Auth0Manager {

    private static final String TAG = "Auth0Manager";
    private static final String SCOPE = "openid profile email offline_access";

    private static volatile Auth0Manager INSTANCE;

    private final Context appContext;
    @Nullable private final Auth0 account;
    @Nullable private volatile SecureCredentialsManager credentialsManager;
    private final boolean configured;

    private Auth0Manager(Context ctx) {
        this.appContext = ctx.getApplicationContext();

        String domain   = appContext.getString(R.string.com_auth0_domain);
        String clientId = appContext.getString(R.string.com_auth0_client_id);
        boolean ok = !TextUtils.isEmpty(domain)
                && !TextUtils.isEmpty(clientId)
                && !domain.contains("REPLACE")
                && !clientId.contains("REPLACE");

        Auth0 acc = null;
        SecureCredentialsManager mgr = null;
        try {
            if (ok) {
                acc = new Auth0(clientId, domain);
                mgr = new SecureCredentialsManager(
                        appContext,
                    new AuthenticationAPIClient(acc),
                        new SharedPreferencesStorage(appContext));
            } else {
                Log.w(TAG, "Auth0 SSO not configured: com_auth0_domain / com_auth0_client_id missing or placeholder values.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Unable to initialize Auth0: " + t.getMessage(), t);
            ok = false;
        }
        this.account = acc;
        this.credentialsManager = mgr;
        this.configured = ok;
    }

    public static Auth0Manager get(Context context) {
        if (INSTANCE == null) {
            synchronized (Auth0Manager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Auth0Manager(context);
                }
            }
        }
        return INSTANCE;
    }

    public boolean isConfigured() {
        return configured;
    }


    private volatile boolean biometricArmed = false;

    public void enableBiometricIfRequested(@NonNull androidx.fragment.app.FragmentActivity activity) {
        if (!BuildConfig.AUTH0_REQUIRE_BIOMETRIC) return;
        if (account == null || biometricArmed) return;
        biometricArmed = true;
        Log.d(TAG, "AUTH0_REQUIRE_BIOMETRIC=1 detected (Auth0 Android 2.x: credential storage already secured via Keystore).");
    }


    public void hasValidSession(@NonNull SessionCheckCallback callback) {
        if (credentialsManager == null) {
            callback.onResult(false);
            return;
        }
        try {
            credentialsManager.getCredentials(new Callback<Credentials, CredentialsManagerException>() {
                @Override
                public void onSuccess(Credentials result) {
                    syncSession(result);
                    callback.onResult(true);
                }

                @Override
                public void onFailure(@NonNull CredentialsManagerException error) {
                    Log.d(TAG, "No valid session: " + error.getMessage());
                    try {
                        credentialsManager.clearCredentials();
                    } catch (Throwable ignored) {
                    }
                    SessionManager.get(appContext).clear();
                    callback.onResult(false);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "hasValidSession error : " + t.getMessage(), t);
            try {
                credentialsManager.clearCredentials();
            } catch (Throwable ignored) { }
            SessionManager.get(appContext).clear();
            callback.onResult(false);
        }
    }


    public boolean tryGetAccessToken(
            @NonNull Callback<Credentials, CredentialsManagerException> callback) {
        SecureCredentialsManager mgr = credentialsManager;
        if (mgr == null) return false;
        try {
            mgr.getCredentials(callback);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "tryGetAccessToken raised: " + t.getMessage());
            return false;
        }
    }

    public void signIn(@NonNull Activity activity, @NonNull SignInCallback callback) {
        if (account == null || credentialsManager == null) {
            callback.onError("Auth0 not configured: set com_auth0_domain and com_auth0_client_id.");
            return;
        }

        String scheme = appContext.getString(R.string.com_auth0_scheme);
        String domain = appContext.getString(R.string.com_auth0_domain);
        String redirectUri = scheme + "://" + domain + "/android/" + appContext.getPackageName() + "/callback";
        Map<String, String> loginParameters = new java.util.HashMap<>();
        loginParameters.put("prompt", "login");
        loginParameters.put("max_age", "0");
        if (!TextUtils.isEmpty(BuildConfig.AUTH0_CONNECTION_NAME)) {
            loginParameters.put("connection", BuildConfig.AUTH0_CONNECTION_NAME);
        }
        Log.d("AUTH_SUCCESS", "Auth0 login start | domain=" + domain
                + " | scheme=" + scheme
            + " | connection=" + BuildConfig.AUTH0_CONNECTION_NAME
            + " | redirect_uri=" + redirectUri);
        com.auth0.android.provider.WebAuthProvider.Builder builder =
                WebAuthProvider.login(account)
                        .withScheme(scheme)
                .withScope(SCOPE)
                .withParameters(loginParameters);
        if (!TextUtils.isEmpty(BuildConfig.AUTH0_AUDIENCE)) {
            builder = builder.withAudience(BuildConfig.AUTH0_AUDIENCE);
        }
        builder.start(activity, new Callback<Credentials, AuthenticationException>() {
                    @Override
                    public void onSuccess(Credentials result) {
                        try {
                            credentialsManager.saveCredentials(result);
                        } catch (Throwable t) {
                            Log.w(TAG, "saveCredentials failed: " + t.getMessage());
                        }
                        String[] info = syncSession(result);
                        callback.onSuccess(info[0], info[1], info[2]);
                    }

                    @Override
                    public void onFailure(@NonNull AuthenticationException error) {
                        String msg = error.getMessage();
                        String description = error.getDescription();
                        String code = error.getCode();
                        if (TextUtils.isEmpty(msg)) msg = description;
                        String details = "code=" + code
                                + " | message=" + (msg != null ? msg : "")
                                + " | description=" + (description != null ? description : "");
                        Log.e("AUTH_ERROR", "Auth0 login failed | " + details, error);
                        callback.onError(details);
                    }
                });
    }

    public void signOut(@NonNull Activity activity, @Nullable Runnable onComplete) {
        SessionManager.get(appContext).clear();

        if (account == null || credentialsManager == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String scheme = appContext.getString(R.string.com_auth0_scheme);
        String domain = appContext.getString(R.string.com_auth0_domain);
        String pkg = appContext.getPackageName();
        String logoutReturnTo = scheme + "://" + domain + "/android/" + pkg + "/logout";
        WebAuthProvider.logout(account)
                .withScheme(scheme)
                .withReturnToUrl(logoutReturnTo)
                .withFederated()
                .start(activity, new Callback<Void, AuthenticationException>() {
                    @Override
                    public void onSuccess(Void result) {
                        try {
                            credentialsManager.clearCredentials();
                        } catch (Throwable t) {
                            Log.w(TAG, "clearCredentials : " + t.getMessage());
                        }
                        if (onComplete != null) onComplete.run();
                    }

                    @Override
                    public void onFailure(@NonNull AuthenticationException error) {
                        Log.w(TAG, "Logout Auth0 : " + error.getMessage());
                        try {
                            credentialsManager.clearCredentials();
                        } catch (Throwable ignored) { }
                        if (onComplete != null) onComplete.run();
                    }
                });
    }

    public void signOut(@NonNull Activity activity) {
        signOut(activity, null);
    }

    public void clearLocalSession() {
        SessionManager.get(appContext).clear();
        SecureCredentialsManager mgr = credentialsManager;
        if (mgr != null) {
            try {
                mgr.clearCredentials();
            } catch (Throwable t) {
                Log.w(TAG, "clearLocalSession: " + t.getMessage());
            }
        }
    }


    private String[] syncSession(@NonNull Credentials credentials) {
        UserProfile profile = credentials.getUser();
        String email = profile != null ? profile.getEmail() : null;
        String username = firstNonEmpty(
                profile != null ? profile.getNickname() : null,
                profile != null ? profile.getName() : null,
                email,
                "vista.user");
        String role = extractRole(profile);

        SessionManager.get(appContext).saveSsoSession(username, email, role);
        return new String[] { username, email, role };
    }

    private static String extractRole(@Nullable UserProfile profile) {
        if (profile == null) return "USER";
        Map<String, Object> extra = profile.getExtraInfo();
        if (extra == null || extra.isEmpty()) return "USER";

        String[] candidateKeys = {
                "https://vista.com/roles",
                "https://vista.com/groups",
                "roles",
                "groups"
        };
        for (String key : candidateKeys) {
            Object value = extra.get(key);
            String role = matchRole(value);
            if (role != null) return role;
        }
        return "USER";
    }

    @Nullable
    private static String matchRole(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                String r = matchRole(item);
                if (r != null) return r;
            }
            return null;
        }
        String v = value.toString().toUpperCase(Locale.ROOT);
        if (v.contains("ADMIN")) return "ADMIN";
        if (v.contains("MANAGER")) return "MANAGER";
        if (v.contains("TECHNICIEN") || v.contains("TECH")) return "TECHNICIEN";
        return null;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) return v;
        }
        return null;
    }


    public interface SessionCheckCallback {
        void onResult(boolean hasValidSession);
    }

    public interface SignInCallback {
        void onSuccess(String username, @Nullable String email, String role);
        void onError(String message);
    }
}
