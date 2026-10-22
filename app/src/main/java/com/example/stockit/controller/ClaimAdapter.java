package com.example.stockit.controller;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.Claim;
import java.util.List;

public class ClaimAdapter extends RecyclerView.Adapter<ClaimAdapter.ViewHolder> {
    private List<Claim> claims;
    private final OnClaimClickListener listener;

    public interface OnClaimClickListener {
        void onClaimClick(Claim claim);
    }

    public ClaimAdapter(List<Claim> claims, OnClaimClickListener listener) {
        this.claims = claims;
        this.listener = listener;
    }

    public void setClaims(List<Claim> claims) {
        this.claims = claims;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_claim, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Claim claim = claims.get(position);
        Context ctx = holder.itemView.getContext();
        
        holder.subject.setText(claim.getSubject());
        holder.description.setText(claim.getDescription());
        holder.sender.setText(ctx.getString(R.string.mov_label_user, claim.getSenderName()));
        
        String localizedStatus = claim.getStatus();
        if ("OUVERT".equals(claim.getStatus())) localizedStatus = ctx.getString(R.string.status_open);
        holder.status.setText(ctx.getString(R.string.status_label, localizedStatus));
        
        String priority = claim.getPriority();
        String localizedPriority = priority;
        if ("HAUTE".equals(priority)) {
            holder.priority.setTextColor(Color.RED);
            localizedPriority = ctx.getString(R.string.priority_high);
        } else if ("MOYENNE".equals(priority)) {
            holder.priority.setTextColor(Color.parseColor("#F39C12"));
            localizedPriority = ctx.getString(R.string.priority_medium);
        } else {
            holder.priority.setTextColor(Color.GREEN);
            localizedPriority = ctx.getString(R.string.priority_low);
        }
        holder.priority.setText(localizedPriority);

        holder.itemView.setOnClickListener(v -> listener.onClaimClick(claim));
    }

    @Override
    public int getItemCount() {
        return claims.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView subject, description, sender, status, priority;
        public ViewHolder(View view) {
            super(view);
            subject = view.findViewById(R.id.claimSubject);
            description = view.findViewById(R.id.claimDescription);
            sender = view.findViewById(R.id.claimSender);
            status = view.findViewById(R.id.claimStatus);
            priority = view.findViewById(R.id.claimPriority);
        }
    }
}
