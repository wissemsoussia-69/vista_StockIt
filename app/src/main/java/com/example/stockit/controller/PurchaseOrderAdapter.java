package com.example.stockit.controller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.PurchaseOrder;
import java.util.List;

public class PurchaseOrderAdapter extends RecyclerView.Adapter<PurchaseOrderAdapter.ViewHolder> {
    private List<PurchaseOrder> orders;
    private final OnOrderActionListener listener;

    public interface OnOrderActionListener {
        void onValidate(PurchaseOrder order);
        void onDelete(PurchaseOrder order);
    }

    public interface OnValidateListener { void onValidate(PurchaseOrder o); }

    public PurchaseOrderAdapter(List<PurchaseOrder> orders, OnValidateListener validateListener) {
        this.orders = orders;
        this.listener = new OnOrderActionListener() {
            @Override public void onValidate(PurchaseOrder order) { validateListener.onValidate(order); }
            @Override public void onDelete(PurchaseOrder order) { /* Ignore */ }
        };
    }

    public PurchaseOrderAdapter(List<PurchaseOrder> orders, OnOrderActionListener listener) {
        this.orders = orders;
        this.listener = listener;
    }

    public void setOrders(List<PurchaseOrder> orders) {
        this.orders = orders;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.purchase_order_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PurchaseOrder order = orders.get(position);
        Context ctx = holder.itemView.getContext();
        
        holder.productName.setText(order.getProductName());
        holder.supplier.setText(ctx.getString(R.string.purchase_supplier_label, order.getSupplier()));
        holder.quantity.setText(ctx.getString(R.string.purchase_qty_label, order.getQuantity()));
        holder.date.setText(order.getDate());
        
        String status = order.getStatus();
        String localizedStatus = status;
        if ("En attente".equals(status)) {
            localizedStatus = ctx.getString(R.string.status_pending);
            holder.status.setTextColor(android.graphics.Color.RED);
            holder.btnValidate.setVisibility(View.VISIBLE);
        } else if ("Reçu".equals(status)) {
            localizedStatus = ctx.getString(R.string.status_received);
            holder.status.setTextColor(android.graphics.Color.GREEN);
            holder.btnValidate.setVisibility(View.GONE);
        }
        holder.status.setText(localizedStatus);

        holder.btnValidate.setOnClickListener(v -> listener.onValidate(order));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(order));
    }

    @Override
    public int getItemCount() {
        return orders.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView productName, supplier, quantity, date, status;
        View btnValidate, btnDelete;

        public ViewHolder(View view) {
            super(view);
            productName = view.findViewById(R.id.orderProductName);
            supplier = view.findViewById(R.id.orderSupplier);
            quantity = view.findViewById(R.id.orderQuantity);
            date = view.findViewById(R.id.orderDate);
            status = view.findViewById(R.id.orderStatus);
            btnValidate = view.findViewById(R.id.btnValidateOrder);
            btnDelete = view.findViewById(R.id.btnDeleteOrder);
        }
    }
}
