package com.example.stockit.controller;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.stockit.R;
import com.example.stockit.util.DeliveryNoteParser.POBlock;

import java.util.List;
import java.util.Set;

public class POBlockAdapter extends RecyclerView.Adapter<POBlockAdapter.VH> {

    public interface OnSelected { void onSelected(POBlock po, int position); }

    private final List<POBlock> items;
    private final Set<Integer> knownIds;
    private final String equipmentHint;
    private final OnSelected callback;
    private int selectedIndex = -1;

    public POBlockAdapter(List<POBlock> items, Set<Integer> knownIds,
                          String equipmentHint, OnSelected cb) {
        this.items = items;
        this.knownIds = knownIds;
        this.equipmentHint = equipmentHint == null ? "" : equipmentHint.toLowerCase();
        this.callback = cb;
    }

    public POBlock getSelected() {
        return (selectedIndex >= 0 && selectedIndex < items.size()) ? items.get(selectedIndex) : null;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_po_block, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        POBlock po = items.get(position);
        h.number.setText(po.number);
        h.desc.setText(po.description == null || po.description.isEmpty()
            ? "(no description)" : po.description);
        h.radio.setChecked(position == selectedIndex);

        Integer id = com.example.stockit.util.DeliveryNoteParser.extractIntFromPoNumber(po.number);
        boolean inDb = id != null && knownIds != null && knownIds.contains(id);

        boolean semanticMatch = !equipmentHint.isEmpty() && po.description != null
                && po.description.toLowerCase().contains(equipmentHint);

        StringBuilder badge = new StringBuilder();
        if (inDb)           badge.append("In database");
        if (semanticMatch)  badge.append(inDb ? "  |  " : "").append("Matches scan");
        h.badge.setText(badge.toString());
        h.badge.setTextColor(semanticMatch ? Color.parseColor("#2E7D32")
                : inDb ? Color.parseColor("#1565C0")
                       : Color.parseColor("#9E9E9E"));

        h.itemView.setOnClickListener(v -> {
            int old = selectedIndex;
            selectedIndex = h.getBindingAdapterPosition();
            if (old >= 0) notifyItemChanged(old);
            notifyItemChanged(selectedIndex);
            if (callback != null) callback.onSelected(po, selectedIndex);
        });
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        RadioButton radio;
        TextView number, desc, badge;
        VH(@NonNull View v) {
            super(v);
            radio  = v.findViewById(R.id.itemPoRadio);
            number = v.findViewById(R.id.itemPoNumber);
            desc   = v.findViewById(R.id.itemPoDesc);
            badge  = v.findViewById(R.id.itemPoBadge);
        }
    }
}
