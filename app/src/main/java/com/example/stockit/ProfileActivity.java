package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.example.stockit.controller.MainController;
import com.example.stockit.model.User;
import com.example.stockit.util.Auth0Manager;
import com.example.stockit.util.SessionManager;

/** Profil (iOS ProfileView) : gamification, fournisseur de connexion, rôle, vraie déconnexion. */
public class ProfileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        Toolbar toolbar = findViewById(R.id.profileToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.nav_profile));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView profileUsername = findViewById(R.id.profileUsername);
        TextView profileEmail = findViewById(R.id.profileEmail);
        TextView profileDept = findViewById(R.id.profileDept);
        TextView profileLocation = findViewById(R.id.profileLocation);

        SessionManager session = SessionManager.get(this);
        User user = MainController.getCurrentUser();
        if (user != null) {
            profileUsername.setText(user.getUsername());
            String email = session.getEmail();
            profileEmail.setText(email != null && !email.isEmpty() ? email
                    : ("auth0".equals(session.getProvider()) ? "—" : L("Compte local (démo)")));

            TextView txtLevel = findViewById(R.id.profileLevel);
            TextView txtPoints = findViewById(R.id.profilePoints);
            TextView txtBadges = findViewById(R.id.profileBadges);
            if (txtLevel != null) txtLevel.setText(L("NIVEAU %@", user.getLevel()));
            if (txtPoints != null) txtPoints.setText(L("%@ POINTS", user.getPoints()));
            String badges = user.getBadges() == null || user.getBadges().isEmpty() ? L("Aucun") : user.getBadges();
            if (txtBadges != null) txtBadges.setText(L("Badges : %@", badges));
        }

        String provider = "auth0".equals(session.getProvider()) ? L("Vista SSO (Okta)") : L("Connexion locale (démo)");
        String role = user != null ? user.getRole() : session.getRole();
        profileDept.setText(L("Rôle : %@", com.example.stockit.util.AppLanguage.LV(role)) + "  ·  " + getString(R.string.profile_dept_it));
        profileLocation.setText(L("Connexion : %@", provider) + "  ·  " + getString(R.string.profile_loc_hq));

        Button logoutButton = findViewById(R.id.logoutButton);
        logoutButton.setOnClickListener(v -> {
            logoutButton.setEnabled(false);
            MainController.logoutLocal();
            session.clear();
            Auth0Manager.get(this).signOut(this, () -> {
                Intent intent = new Intent(ProfileActivity.this, SplashActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    private static String L(String key, Object... args) { return com.example.stockit.util.AppLanguage.L(key, args); }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
