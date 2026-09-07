package com.example.stockit.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.R;
import com.example.stockit.model.JiraTicket;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TicketAdapter extends RecyclerView.Adapter<TicketAdapter.VH> {

    public interface OnPicked { void onPicked(JiraTicket t); }

    public enum FilterMode { ALL, HIGHEST, OVERDUE, MINE }

    private static final SimpleDateFormat ISO_DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private final List<JiraTicket> all = new ArrayList<>();
    private final List<JiraTicket> visible = new ArrayList<>();
    private final OnPicked callback;

    private String query = "";
    private FilterMode mode = FilterMode.ALL;
    private String currentUserAssignee;

    public TicketAdapter(OnPicked cb) { this.callback = cb; }

    public void setCurrentUserAssignee(String name) { this.currentUserAssignee = name; }

    public void setData(List<JiraTicket> data) {
        all.clear();
        if (data != null) all.addAll(data);
        applyFilters();
    }

    public void filter(String q) {
        this.query = q == null ? "" : q.toLowerCase().trim();
        applyFilters();
    }

    public void setFilterMode(FilterMode m) {
        this.mode = m == null ? FilterMode.ALL : m;
        applyFilters();
    }

    public int visibleCount() { return visible.size(); }

    public JiraTicket getVisibleAt(int position) {
        if (position < 0 || position >= visible.size()) return null;
        return visible.get(position);
    }

    public JiraTicket removeAt(int position) {
        if (position < 0 || position >= visible.size()) return null;
        JiraTicket t = visible.remove(position);
        all.remove(t);
        notifyItemRemoved(position);
        return t;
    }

    private void applyFilters() {
        visible.clear();
        long now = System.currentTimeMillis();
        for (JiraTicket t : all) {
            if (mode == FilterMode.HIGHEST && t.priorityWeight() < 5) continue;
            if (mode == FilterMode.OVERDUE) {
                if (t.dueDate == null) continue;
                try {
                    Date d = ISO_DATE.parse(t.dueDate);
                    if (d == null || d.getTime() >= now) continue;
                } catch (Exception e) { continue; }
            }
            if (mode == FilterMode.MINE) {
                if (currentUserAssignee == null || t.assignee == null) continue;
                if (!t.assignee.toLowerCase().contains(currentUserAssignee.toLowerCase())) continue;
            }
            if (!query.isEmpty()) {
                boolean hit = (t.key != null && t.key.toLowerCase().contains(query))
                        || (t.summary != null && t.summary.toLowerCase().contains(query))
                        || (t.description != null && t.description.toLowerCase().contains(query))
                        || (t.priority != null && t.priority.toLowerCase().contains(query));
                if (!hit) continue;
            }
            visible.add(t);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ticket, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JiraTicket t = visible.get(position);
        h.key.setText(t.key);
        h.summary.setText(t.summary == null ? "" : t.summary);

        StringBuilder meta = new StringBuilder();
        meta.append(t.status == null ? "?" : t.status);
        if (t.assignee != null) meta.append("  |  @").append(t.assignee);
        h.meta.setText(meta.toString());

        String prio = t.priority == null ? "?" : t.priority;
        h.priority.setText(prio);
        int w = t.priorityWeight();
        int pillBg;
        int barColorRes;
        if (w >= 5)      { pillBg = R.drawable.bg_priority_pill_urgent;   barColorRes = R.color.vista_coral; }
        else if (w == 4) { pillBg = R.drawable.bg_priority_pill_high;     barColorRes = R.color.vista_amber; }
        else if (w == 3) { pillBg = R.drawable.bg_priority_pill_medium;   barColorRes = R.color.vista_blue; }
        else             { pillBg = R.drawable.bg_priority_pill_low;      barColorRes = R.color.vista_navy_faint; }
        h.priority.setBackgroundResource(pillBg);
        h.priority.setTextColor(
                ContextCompat.getColor(h.itemView.getContext(),
                        w <= 2 ? R.color.vista_navy_muted : R.color.white));
        if (h.priorityBar != null) {
            h.priorityBar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(h.itemView.getContext(), barColorRes)));
        }

        if (h.avatar != null) {
            h.avatar.setText(initialsOf(t.assignee));
        }

        if (h.sla != null) {
            String label = slaLabel(t.dueDate);
            if (label == null) {
                h.sla.setVisibility(View.GONE);
            } else {
                h.sla.setVisibility(View.VISIBLE);
                h.sla.setText(label);
                boolean overdue = label.startsWith("!") || label.startsWith("SLA");
                h.sla.setBackgroundResource(
                        overdue ? R.drawable.bg_chip_urgent : R.drawable.bg_chip_navy);
                h.sla.setTextColor(ContextCompat.getColor(h.itemView.getContext(),
                        overdue ? R.color.vista_coral : R.color.vista_navy));
            }
        }

        boolean isUrgent = w >= 4;
        if (h.urgencyDot != null) {
            if (isUrgent) {
                h.urgencyDot.setVisibility(View.VISIBLE);
                if (h.pulseAnim == null) {
                    h.pulseAnim = AnimationUtils.loadAnimation(
                            h.itemView.getContext(), R.anim.pulse_urgency);
                }
                h.urgencyDot.startAnimation(h.pulseAnim);
            } else {
                h.urgencyDot.clearAnimation();
                h.urgencyDot.setVisibility(View.GONE);
            }
        }

        if (t.description != null && !t.description.isEmpty()) {
            h.desc.setVisibility(View.VISIBLE);
            h.desc.setText(t.description);
        } else {
            h.desc.setVisibility(View.GONE);
        }

        View.OnClickListener pick = v -> {
            if (callback != null) callback.onPicked(t);
        };
        h.itemView.setOnClickListener(pick);
        View card = h.itemView.findViewById(R.id.itemTicketCard);
        if (card != null) card.setOnClickListener(pick);
    }

    private static String initialsOf(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && sb.length() < 2; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.length() > 0 ? sb.toString() : "?";
    }

    private static String slaLabel(String dueDate) {
        if (dueDate == null || dueDate.isEmpty()) return null;
        try {
            Date due = ISO_DATE.parse(dueDate);
            if (due == null) return null;
            long deltaMs = due.getTime() - System.currentTimeMillis();
            long days = TimeUnit.MILLISECONDS.toDays(deltaMs);
            long hours = TimeUnit.MILLISECONDS.toHours(deltaMs);
            if (deltaMs < 0) return "SLA " + days + "j";              // ex: SLA -2j
            if (hours < 24)  return "SLA " + Math.max(hours, 1) + "h"; // ex: SLA 3h (urgent)
            return "Due " + days + "j";                                // ex: Due 5j
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        if (holder.urgencyDot != null) holder.urgencyDot.clearAnimation();
    }

    @Override public int getItemCount() { return visible.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView key, summary, meta, priority, desc, avatar, sla;
        View urgencyDot, priorityBar;
        Animation pulseAnim;
        VH(@NonNull View v) {
            super(v);
            key         = v.findViewById(R.id.itemTicketKey);
            summary     = v.findViewById(R.id.itemTicketSummary);
            meta        = v.findViewById(R.id.itemTicketMeta);
            priority    = v.findViewById(R.id.itemTicketPriority);
            desc        = v.findViewById(R.id.itemTicketDesc);
            urgencyDot  = v.findViewById(R.id.itemTicketUrgencyDot);
            priorityBar = v.findViewById(R.id.itemTicketPriorityBar);
            avatar      = v.findViewById(R.id.itemTicketAvatar);
            sla         = v.findViewById(R.id.itemTicketSla);
        }
    }
}

