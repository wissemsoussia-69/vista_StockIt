package com.example.stockit.controller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.StockMovement;
import java.util.List;

public class StockMovementAdapter extends RecyclerView.Adapter<StockMovementAdapter.ViewHolder> {

    private List<StockMovement> movements;

    public StockMovementAdapter(List<StockMovement> movements) {
        this.movements = movements;
    }

    public void setMovements(List<StockMovement> movements) {
        this.movements = movements;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_stock_movement, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StockMovement movement = movements.get(position);
        Context ctx = holder.itemView.getContext();
        
        String icon = "📥";
        if ("OUT".equals(movement.getType())) {
            icon = "📤";
        } else if ("TRANSFER".equals(movement.getType())) {
            icon = "↔️";
        }
        
        holder.typeIcon.setText(icon);
        holder.productName.setText(
            com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(movement.getProductName()));
        holder.dateTime.setText(movement.getDate());
        holder.quantity.setText(ctx.getString(R.string.mov_label_qty, movement.getQuantity()));
        holder.userName.setText(ctx.getString(R.string.mov_label_user, movement.getUserName()));
        holder.reason.setText(ctx.getString(R.string.mov_label_reason, movement.getReason()));
        
        if (movement.getComment() != null && !movement.getComment().isEmpty()) {
            holder.comment.setText(ctx.getString(R.string.mov_label_comment, movement.getComment()));
            holder.comment.setVisibility(View.VISIBLE);
        } else {
            holder.comment.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return movements.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView typeIcon, productName, dateTime, quantity, userName, reason, comment;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            typeIcon = itemView.findViewById(R.id.movementTypeIcon);
            productName = itemView.findViewById(R.id.movementProductName);
            dateTime = itemView.findViewById(R.id.movementDateTime);
            quantity = itemView.findViewById(R.id.movementQuantity);
            userName = itemView.findViewById(R.id.movementUserDetails);
            reason = itemView.findViewById(R.id.movementReason);
            comment = itemView.findViewById(R.id.movementComment);
        }
    }
}
