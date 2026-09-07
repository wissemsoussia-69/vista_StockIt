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
import com.example.stockit.model.AIInsight;
import java.util.List;

public class AIInsightAdapter extends RecyclerView.Adapter<AIInsightAdapter.ViewHolder> {
    private List<AIInsight> insights;

    public AIInsightAdapter(List<AIInsight> insights) {
        this.insights = insights;
    }

    public void setInsights(List<AIInsight> insights) {
        this.insights = insights;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ai_insight, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AIInsight insight = insights.get(position);
        Context ctx = holder.itemView.getContext();
        
        holder.title.setText(insight.getTitle());
        holder.description.setText(insight.getDescription());
        
        String typeName = insight.getType().name();
        String localizedType = typeName;
        switch (typeName) {
            case "RECOMMENDATION": localizedType = ctx.getString(R.string.ai_type_recommendation); break;
            case "ANOMALY": localizedType = ctx.getString(R.string.ai_type_anomaly); break;
            case "RISK": localizedType = ctx.getString(R.string.ai_type_risk); break;
            case "PREDICTION": localizedType = ctx.getString(R.string.ai_type_prediction); break;
            case "PRICE_SUGGESTION": localizedType = ctx.getString(R.string.ai_type_price); break;
        }
        holder.type.setText(localizedType);

        // Color based on priority
        String priorityStr = insight.getPriority();
        String localizedPriority = priorityStr;
        if ("HIGH".equals(priorityStr)) {
            holder.priority.setTextColor(Color.RED);
            localizedPriority = ctx.getString(R.string.priority_high);
        } else if ("MEDIUM".equals(priorityStr)) {
            holder.priority.setTextColor(Color.YELLOW);
            localizedPriority = ctx.getString(R.string.priority_medium);
        } else {
            holder.priority.setTextColor(Color.CYAN);
            localizedPriority = ctx.getString(R.string.priority_low);
        }
        holder.priority.setText(ctx.getString(R.string.priority_label_full, localizedPriority));
    }

    @Override
    public int getItemCount() {
        return insights.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title, description, type, priority;
        public ViewHolder(View view) {
            super(view);
            title = view.findViewById(R.id.aiTitle);
            description = view.findViewById(R.id.aiDescription);
            type = view.findViewById(R.id.aiType);
            priority = view.findViewById(R.id.aiPriority);
        }
    }
}
