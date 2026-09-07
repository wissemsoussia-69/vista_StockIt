package com.example.stockit;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

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

        android.widget.TextView profileUsername = findViewById(R.id.profileUsername);
        android.widget.TextView profileEmail = findViewById(R.id.profileEmail);
        android.widget.TextView profileDept = findViewById(R.id.profileDept);
        android.widget.TextView profileLocation = findViewById(R.id.profileLocation);

        com.example.stockit.model.User user = com.example.stockit.controller.MainController.getCurrentUser();
        if (user != null) {
            profileUsername.setText(user.getUsername());
            profileEmail.setText(user.getUsername() + "@vistaprint.com");
            
            // --- GAMIFICATION ---
            android.widget.TextView txtLevel = findViewById(R.id.profileLevel);
            android.widget.TextView txtPoints = findViewById(R.id.profilePoints);
            android.widget.TextView txtBadges = findViewById(R.id.profileBadges);
            
            if (txtLevel != null) txtLevel.setText("NIVEAU " + user.getLevel());
            if (txtPoints != null) txtPoints.setText(user.getPoints() + " POINTS");
            if (txtBadges != null) txtBadges.setText("Badges : " + (user.getBadges().isEmpty() ? "Aucun" : user.getBadges()));
        }
        
        profileDept.setText(getString(R.string.profile_dept, getString(R.string.profile_dept_it)));
        profileLocation.setText(getString(R.string.profile_location, getString(R.string.profile_loc_hq)));

        Button logoutButton = findViewById(R.id.logoutButton);
        logoutButton.setOnClickListener(v -> {
            Intent intent = new Intent(ProfileActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        // Replace onBackPressed() with the new dispatcher
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
