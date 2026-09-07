package com.example.stockit.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.auth0.android.authentication.storage.CredentialsManagerException;
import com.auth0.android.callback.Callback;
import com.auth0.android.result.Credentials;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * StockIT — Interceptor OkHttp qui injecte {@code Authorization: Bearer <accessToken>}
 * sur chaque requête sortante, en s'appuyant sur {@link Auth0Manager}.
 *
 * <p>Comportement :</p>
 * <ul>
 *   <li>Si Auth0 est configuré ET qu'une session valide existe, le token
 *       est récupéré (rafraîchi via refresh_token si nécessaire) puis ajouté.</li>
 *   <li>Sinon la requête part telle quelle (utile pour les endpoints publics
 *       ou en mode démo/offline). Aucun blocage.</li>
 *   <li>Si l'appelant fournit déjà un header {@code Authorization} (ex. Basic
 *       auth Jira, clé API Hugging Face…), on n'y touche pas.</li>
 * </ul>
 *
 * <p>À attacher uniquement aux clients OkHttp qui parlent au backend Vista
 * — pas aux clients qui utilisent leur propre schéma d'auth (Jira, Gemini,
 * Cimpress Gateway, etc.).</p>
 */
public final class AuthBearerInterceptor implements Interceptor {

    private static final String TAG = "AuthBearerInterceptor";
    private static final long TOKEN_TIMEOUT_SECONDS = 10L;

    private final Auth0Manager auth0;

    public AuthBearerInterceptor(@NonNull Context context) {
        this.auth0 = Auth0Manager.get(context);
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();

        // Respecter un éventuel header Authorization déjà positionné (Basic Jira, etc.).
        if (original.header("Authorization") != null) {
            return chain.proceed(original);
        }

        String token = fetchAccessTokenBlocking();
        if (token == null || token.isEmpty()) {
            // Pas de session SSO valide : on laisse passer la requête sans Bearer.
            // Le backend renverra 401 le cas échéant, l'UI redirigera vers login.
            return chain.proceed(original);
        }

        Request authed = original.newBuilder()
                .header("Authorization", "Bearer " + token)
                .build();
        return chain.proceed(authed);
    }

    /**
     * Bloque le thread OkHttp le temps que {@link Auth0Manager} restitue les
     * credentials (rafraîchissement inclus). OkHttp exécute déjà les interceptors
     * sur un thread de fond, donc l'attente est sans risque pour l'UI.
     */
    private String fetchAccessTokenBlocking() {
        final AtomicReference<String> tokenRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);

        boolean requested = auth0.tryGetAccessToken(new Callback<Credentials, CredentialsManagerException>() {
            @Override
            public void onSuccess(Credentials result) {
                tokenRef.set(result != null ? result.getAccessToken() : null);
                latch.countDown();
            }

            @Override
            public void onFailure(@NonNull CredentialsManagerException error) {
                Log.d(TAG, "Pas de token disponible : " + error.getMessage());
                latch.countDown();
            }
        });

        if (!requested) {
            return null; // Auth0 non configuré : rien à injecter.
        }

        try {
            latch.await(TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return tokenRef.get();
    }
}
