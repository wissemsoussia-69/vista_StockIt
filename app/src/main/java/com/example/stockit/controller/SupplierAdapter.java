package com.example.stockit.controller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.Supplier;
import java.util.List;

public class SupplierAdapter extends RecyclerView.Adapter<SupplierAdapter.ViewHolder> {
    private List<Supplier> suppliers;
    private final OnSupplierActionListener listener;

    public interface OnSupplierActionListener {
        void onEdit(Supplier supplier);
        void onDelete(Supplier supplier);
        void onOrder(Supplier supplier);
    }

    public interface OnEditListener { void onEdit(Supplier s); }
    public interface OnDeleteListener { void onDelete(Supplier s); }

    public SupplierAdapter(List<Supplier> suppliers, OnEditListener editListener, OnDeleteListener deleteListener) {
        this.suppliers = suppliers;
        this.listener = new OnSupplierActionListener() {
            @Override public void onEdit(Supplier supplier) { editListener.onEdit(supplier); }
            @Override public void onDelete(Supplier supplier) { deleteListener.onDelete(supplier); }
            @Override public void onOrder(Supplier supplier) { /* Optional */ }
        };
    }

    public SupplierAdapter(List<Supplier> suppliers, OnSupplierActionListener listener) {
        this.suppliers = suppliers;
        this.listener = listener;
    }

    public void setSuppliers(List<Supplier> suppliers) {
        this.suppliers = suppliers;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_supplier, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Supplier s = suppliers.get(position);
        Context ctx = holder.itemView.getContext();
        
        holder.name.setText(s.getName());
        holder.discount.setText("-" + s.getDiscount() + "%");
        holder.contact.setText(s.getEmail() + " | " + s.getPhone());
        holder.address.setText(s.getAddress());
        holder.leadTime.setText(ctx.getString(R.string.sup_lead_time, s.getLeadTime()));

        holder.btnEdit.setOnClickListener(v -> listener.onEdit(s));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(s));
        holder.btnOrder.setOnClickListener(v -> listener.onOrder(s));
    }

    @Override
    public int getItemCount() {
        return suppliers.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, discount, contact, address, leadTime;
        ImageButton btnEdit, btnDelete, btnOrder;

        public ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.supplierName);
            discount = view.findViewById(R.id.supplierDiscount);
            contact = view.findViewById(R.id.supplierContact);
            address = view.findViewById(R.id.supplierAddress);
            leadTime = view.findViewById(R.id.supplierLeadTime);
            btnEdit = view.findViewById(R.id.btnEditSupplier);
            btnDelete = view.findViewById(R.id.btnDeleteSupplier);
            btnOrder = view.findViewById(R.id.btnOrderSupplier);
        }
    }
}
