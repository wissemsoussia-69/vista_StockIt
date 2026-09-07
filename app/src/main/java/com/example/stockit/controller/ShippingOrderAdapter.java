package com.example.stockit.controller;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.ShippingOrder;
import java.util.List;

public class ShippingOrderAdapter extends RecyclerView.Adapter<ShippingOrderAdapter.ViewHolder> {
    private List<ShippingOrder> orders;
    private final OnShippingActionListener listener;

    public interface OnShippingActionListener {
        void onPrepare(ShippingOrder order);
        void onLabel(ShippingOrder order);
        void onDelete(ShippingOrder order);
    }

    public ShippingOrderAdapter(List<ShippingOrder> orders, OnShippingActionListener listener) {
        this.orders = orders;
        this.listener = listener;
    }

    public void setOrders(List<ShippingOrder> orders) {
        this.orders = orders;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shipping_order, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ShippingOrder order = orders.get(position);
        Context ctx = holder.itemView.getContext();
        
        holder.customer.setText(order.getCustomerName());
        holder.items.setText(ctx.getString(R.string.ship_item_label, order.getItemsSummary()));
        holder.ticket.setText(ctx.getString(R.string.ship_ticket_label, (order.getTicketNumber() != null ? order.getTicketNumber() : "-")));
        holder.tracking.setText(ctx.getString(R.string.ship_tracking_label, (order.getTrackingNumber().isEmpty() ? "-" : order.getTrackingNumber())));

        String status = order.getStatus();
        String localizedStatus = status;
        
        switch (status) {
            case "PREPARING":
                localizedStatus = ctx.getString(R.string.status_preparing);
                holder.status.setBackgroundColor(Color.CYAN);
                holder.status.setTextColor(Color.BLACK);
                break;
            case "SHIPPED":
                localizedStatus = ctx.getString(R.string.status_shipped);
                holder.status.setBackgroundColor(Color.BLUE);
                holder.status.setTextColor(Color.WHITE);
                break;
            case "DELIVERED":
                localizedStatus = ctx.getString(R.string.status_delivered);
                holder.status.setBackgroundColor(Color.GREEN);
                holder.status.setTextColor(Color.BLACK);
                break;
            case "PENDING":
                localizedStatus = ctx.getString(R.string.status_pending);
                holder.status.setBackgroundColor(Color.YELLOW);
                holder.status.setTextColor(Color.BLACK);
                break;
            default:
                holder.status.setBackgroundColor(Color.YELLOW);
                holder.status.setTextColor(Color.BLACK);
                break;
        }
        holder.status.setText(localizedStatus);

        holder.btnPrepare.setOnClickListener(v -> listener.onPrepare(order));
        holder.btnLabel.setOnClickListener(v -> listener.onLabel(order));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(order));
        
        holder.btnJira.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse("https://vistaprint.atlassian.net/jira/servicedesk/projects/SD/queues/custom/152"));
            v.getContext().startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return orders.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView customer, items, status, tracking, ticket;
        ImageButton btnPrepare, btnLabel, btnDelete;
        android.widget.Button btnJira;

        public ViewHolder(View view) {
            super(view);
            customer = view.findViewById(R.id.shipCustomerName);
            items = view.findViewById(R.id.shipItemsSummary);
            ticket = view.findViewById(R.id.shipTicketNumber);
            status = view.findViewById(R.id.shipStatus);
            tracking = view.findViewById(R.id.shipTracking);
            btnPrepare = view.findViewById(R.id.btnPrepare);
            btnLabel = view.findViewById(R.id.btnLabel);
            btnDelete = view.findViewById(R.id.btnDeleteShip);
            btnJira = view.findViewById(R.id.btnJira);
        }
    }
}
