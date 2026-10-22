package com.example.stockit.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.Product;
import java.util.List;

public class ArticleAdapter extends RecyclerView.Adapter<ArticleAdapter.ViewHolder> {
    private List<Product> articles;
    private final OnArticleActionListener listener;

    public interface OnArticleActionListener {
        void onEdit(Product product);
        void onDelete(Product product);
        void onDetail(Product product);
    }

    public ArticleAdapter(List<Product> articles, OnArticleActionListener listener) {
        this.articles = articles;
        this.listener = listener;
    }

    public void setArticles(List<Product> articles) {
        this.articles = articles;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.article_table_row, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Product p = articles.get(position);
        holder.name.setText(p.getName());
        holder.category.setText(p.getCategory());
        holder.qty.setText(String.valueOf(p.getQuantity()));
        holder.price.setText(String.valueOf(p.getUnitPrice()));
        holder.mfgDate.setText(p.getManufacturingDate());
        holder.expDate.setText(p.getExpirationDate());
        
        holder.btnEdit.setOnClickListener(v -> listener.onEdit(p));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(p));
        holder.itemView.setOnClickListener(v -> listener.onDetail(p));
    }

    @Override
    public int getItemCount() {
        return articles.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, category, qty, price, mfgDate, expDate;
        ImageButton btnEdit, btnDelete;

        public ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.rowName);
            category = view.findViewById(R.id.rowCategory);
            qty = view.findViewById(R.id.rowQty);
            price = view.findViewById(R.id.rowPrice);
            mfgDate = view.findViewById(R.id.rowMfgDate);
            expDate = view.findViewById(R.id.rowExpDate);
            btnEdit = view.findViewById(R.id.btnEditArticle);
            btnDelete = view.findViewById(R.id.btnDeleteArticle);
        }
    }
}
