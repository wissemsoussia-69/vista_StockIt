package com.example.stockit.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.StockMovement;
import java.util.List;

public class TimelineAdapter extends RecyclerView.Adapter<TimelineAdapter.ViewHolder> {
    private final List<StockMovement> movements;

    public TimelineAdapter(List<StockMovement> movements) {
        this.movements = movements;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_timeline, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StockMovement m = movements.get(position);
        holder.date.setText(m.getDate());
        holder.title.setText(com.example.stockit.util.AppLanguage.LV(m.getReason()));
        holder.user.setText(com.example.stockit.util.AppLanguage.L("Par %@", m.getUserName() != null ? m.getUserName() : com.example.stockit.util.AppLanguage.L("Inconnu")));
        
        if (m.getComment() != null && !m.getComment().isEmpty()) {
            holder.comment.setText(com.example.stockit.util.AppLanguage.L("Note : %@", m.getComment()));
            holder.comment.setVisibility(View.VISIBLE);
        } else {
            holder.comment.setVisibility(View.GONE);
        }

        // Gérer les lignes de la frise
        holder.lineTop.setVisibility(position == 0 ? View.INVISIBLE : View.VISIBLE);
        holder.lineBottom.setVisibility(position == movements.size() - 1 ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return movements.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView date, title, user, comment;
        View lineTop, lineBottom;
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            date = itemView.findViewById(R.id.timelineDate);
            title = itemView.findViewById(R.id.timelineTitle);
            user = itemView.findViewById(R.id.timelineUser);
            comment = itemView.findViewById(R.id.timelineComment);
            lineTop = itemView.findViewById(R.id.lineTop);
            lineBottom = itemView.findViewById(R.id.lineBottom);
        }
    }
}
