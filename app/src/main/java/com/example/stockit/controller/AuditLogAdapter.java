package com.example.stockit.controller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.AuditLog;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AuditLogAdapter extends RecyclerView.Adapter<AuditLogAdapter.ViewHolder> {
    private List<AuditLog> logs;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault());

    public AuditLogAdapter(List<AuditLog> logs) {
        this.logs = logs;
    }

    public void setLogs(List<AuditLog> logs) {
        this.logs = logs;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_audit_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuditLog log = logs.get(position);
        Context ctx = holder.itemView.getContext();
        
        holder.action.setText(log.getAction());
        holder.details.setText(log.getDetails());
        holder.user.setText(ctx.getString(R.string.mov_label_user, log.getUserId()));
        holder.date.setText(dateFormat.format(new Date(log.getTimestamp())));
        holder.device.setText(log.getDevice());
    }

    @Override
    public int getItemCount() {
        return logs.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView action, details, user, date, device;

        public ViewHolder(View view) {
            super(view);
            action = view.findViewById(R.id.auditAction);
            details = view.findViewById(R.id.auditDetails);
            user = view.findViewById(R.id.auditUser);
            date = view.findViewById(R.id.auditDate);
            device = view.findViewById(R.id.auditDevice);
        }
    }
}
