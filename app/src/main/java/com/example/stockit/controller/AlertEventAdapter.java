package com.example.stockit.controller;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.R;
import com.example.stockit.model.AlertEvent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AlertEventAdapter extends RecyclerView.Adapter<AlertEventAdapter.ViewHolder> {
    private final List<AlertEvent> events;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());

    public AlertEventAdapter(List<AlertEvent> events) {
        this.events = events;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_alert_event, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AlertEvent event = events.get(position);
        holder.product.setText(event.getProductName());
        holder.meta.setText("Qty " + event.getQuantity() + " | channel " + event.getChannel());

        String days = event.getPredictedDays() < 0 ? "stable" : (event.getPredictedDays() + " d");
        holder.forecast.setText("Threshold " + event.getThreshold()
            + " | Smart threshold " + event.getDynamicThreshold()
            + " | Estimated stock-out " + days);

        holder.status.setText(event.getStatus());
        holder.timestamp.setText(dateFormat.format(new Date(event.getCreatedAt())));
        holder.details.setText(event.getDetails() == null ? "" : event.getDetails());

        if ("SENT".equalsIgnoreCase(event.getStatus())) {
            holder.status.setBackgroundColor(Color.parseColor("#2E7D32"));
        } else if ("FAILED".equalsIgnoreCase(event.getStatus())) {
            holder.status.setBackgroundColor(Color.parseColor("#C62828"));
        } else {
            holder.status.setBackgroundColor(Color.parseColor("#EF6C00"));
        }
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView product;
        TextView meta;
        TextView forecast;
        TextView status;
        TextView timestamp;
        TextView details;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            product = itemView.findViewById(R.id.alertProduct);
            meta = itemView.findViewById(R.id.alertMeta);
            forecast = itemView.findViewById(R.id.alertForecast);
            status = itemView.findViewById(R.id.alertStatus);
            timestamp = itemView.findViewById(R.id.alertTimestamp);
            details = itemView.findViewById(R.id.alertDetails);
        }
    }
}
