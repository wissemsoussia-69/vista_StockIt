package com.example.stockit;

import android.app.Application;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.example.stockit.util.Auth0Manager;

public final class StockItApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStop(LifecycleOwner owner) {
                Auth0Manager.get(getApplicationContext()).clearLocalSession();
            }
        });
    }
}
