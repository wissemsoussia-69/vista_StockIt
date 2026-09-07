package com.example.stockit.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.Product;
import java.util.List;

public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {
    private List<Product> products;
    private final OnProductActionListener listener;

    public interface OnProductActionListener {
        void onUpdateQuantity(Product product, int delta);
        void onShowDetails(Product product);
        void onShowMovement(Product product);
    }

    public ProductAdapter(List<Product> products, OnProductActionListener listener) {
        this.products = products;
        this.listener = listener;
    }

    public void setProducts(List<Product> products) {
        this.products = products;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.product_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Product p = products.get(position);
        android.content.Context ctx = holder.itemView.getContext();
        
        holder.name.setText(com.example.stockit.util.LegacyTextNormalizer.toEnglishProductName(p.getName()));
        holder.qty.setText(String.valueOf(p.getQuantity()));

        if (p.getQuantity() <= p.getMinThreshold()) {
            holder.qty.setTextColor(android.graphics.Color.RED);
            holder.qty.setText(p.getQuantity() + " !");
        } else {
            holder.qty.setTextColor(android.graphics.Color.BLACK);
        }
        
        holder.desc.setText(p.getDescription());

        String icon = "[PKG]";
        String cat = p.getCategory().toLowerCase();
        if (cat.contains("computer") || cat.contains("laptop")) icon = "[PC]";
        else if (cat.contains("keyboard")) icon = "[KB]";
        else if (cat.contains("screen") || cat.contains("monitor")) icon = "[MON]";
        else if (cat.contains("mouse")) icon = "[MSE]";
        else if (cat.contains("cable")) icon = "[CBL]";

        holder.icon.setText(icon);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onShowDetails(p);
        });

        holder.icon.setOnClickListener(v -> {
            if (listener != null) listener.onShowMovement(p);
        });

        holder.btnPlus.setOnClickListener(v -> {
            android.util.Log.d("QtyDebug", "btnPlus clicked for " + p.getName() + " listener=" + (listener != null));
            if (listener != null) listener.onUpdateQuantity(p, 1);
        });
        holder.btnMinus.setOnClickListener(v -> {
            android.util.Log.d("QtyDebug", "btnMinus clicked for " + p.getName() + " listener=" + (listener != null));
            if (listener != null) listener.onUpdateQuantity(p, -1);
        });
    }

    @Override
    public int getItemCount() {
        return products.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, desc, qty, icon;
        Button btnPlus, btnMinus;

        public ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.productName);
            desc = view.findViewById(R.id.productDesc);
            qty = view.findViewById(R.id.productQty);
            icon = view.findViewById(R.id.categoryIcon);
            btnPlus = view.findViewById(R.id.btnPlus);
            btnMinus = view.findViewById(R.id.btnMinus);
        }
    }
}
