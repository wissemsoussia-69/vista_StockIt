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

        if (original.header("Authorization") != null) {
            return chain.proceed(original);
        }

        String token = fetchAccessTokenBlocking();
        if (token == null || token.isEmpty()) {
            return chain.proceed(original);
        }

        Request authed = original.newBuilder()
                .header("Authorization", "Bearer " + token)
                .build();
        return chain.proceed(authed);
    }

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
            return null; // Auth0 not configured: nothing to inject.
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
