package com.example.stockit.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.Category;
import java.util.List;

public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {
    private List<Category> categories;
    private final OnCategoryActionListener listener;

    public interface OnCategoryActionListener {
        void onEdit(Category category);
        void onDelete(Category category);
    }

    public interface OnEditListener { void onEdit(Category c); }
    public interface OnDeleteListener { void onDelete(Category c); }

    public CategoryAdapter(List<Category> categories, OnEditListener editListener, OnDeleteListener deleteListener) {
        this.categories = categories;
        this.listener = new OnCategoryActionListener() {
            @Override public void onEdit(Category category) { editListener.onEdit(category); }
            @Override public void onDelete(Category category) { deleteListener.onDelete(category); }
        };
    }

    public CategoryAdapter(List<Category> categories, OnCategoryActionListener listener) {
        this.categories = categories;
        this.listener = listener;
    }

    public void setCategories(List<Category> categories) {
        this.categories = categories;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.category_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Category cat = categories.get(position);
        String name = cat.getName();
        if (cat.getParentId() != null) {
            name = " └─ " + name;
        }
        holder.text.setText(name);
        
        if (cat.getLabelColor() != null) {
            try {
                holder.colorIndicator.setBackgroundColor(android.graphics.Color.parseColor(cat.getLabelColor()));
            } catch (Exception e) {
                holder.colorIndicator.setBackgroundColor(android.graphics.Color.GRAY);
            }
        }

        holder.btnEdit.setOnClickListener(v -> listener.onEdit(cat));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(cat));
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text;
        View colorIndicator;
        ImageButton btnEdit, btnDelete;
        
        public ViewHolder(View view) {
            super(view);
            text = view.findViewById(R.id.catName);
            colorIndicator = view.findViewById(R.id.colorIndicator);
            btnEdit = view.findViewById(R.id.btnEditCat);
            btnDelete = view.findViewById(R.id.btnDeleteCat);
        }
    }
}
