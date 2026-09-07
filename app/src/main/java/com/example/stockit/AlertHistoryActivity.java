package com.example.stockit;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.controller.AlertEventAdapter;
import com.example.stockit.controller.MainController;
import com.example.stockit.model.AlertEvent;

import java.util.List;

public class AlertHistoryActivity extends AppCompatActivity {

    private MainController controller;
    private RecyclerView recycler;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alert_history);

        controller = MainController.getInstance(this);
        recycler = findViewById(R.id.alertHistoryRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.alertHistoryToolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(getString(R.string.alert_history_title));
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        controller.getAlertEvents(this::bindEvents);
    }

    private void bindEvents(List<AlertEvent> events) {
        recycler.setAdapter(new AlertEventAdapter(events));
    }
}
