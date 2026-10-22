package com.example.stockit.controller;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.User;
import java.util.List;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {
    private final List<User> users;
    private final OnProfileClickListener listener;

    public interface OnProfileClickListener {
        void onProfileClick(User user);
    }

    public ProfileAdapter(List<User> users, OnProfileClickListener listener) {
        this.users = users;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        User user = users.get(position);
        holder.name.setText(user.getUsername());
        
        // --- LOGIQUE PHOTO ---
        // Chercher une image dans drawable nommée nadhem, nour, eya, majdi ou zied
        String fileName = user.getUsername().toLowerCase();
        int imageId = holder.itemView.getContext().getResources().getIdentifier(
                fileName, "drawable", holder.itemView.getContext().getPackageName());

        if (imageId != 0) {
            // PHOTO TROUVÉE
            holder.profileImage.setImageResource(imageId);
            holder.profileImage.setVisibility(View.VISIBLE);
            holder.initial.setVisibility(View.GONE);
        } else {
            // PAS DE PHOTO (MODE BULLLE)
            holder.profileImage.setVisibility(View.GONE);
            holder.initial.setVisibility(View.VISIBLE);
            holder.initial.setText(user.getUsername().substring(0, 1).toUpperCase());
            
            // Couleurs style Netflix
            int[] colors = {0xFFE74C3C, 0xFF3498DB, 0xFF2ECC71, 0xFFF1C40F, 0xFF9B59B6, 0xFF34495E};
            holder.initial.setBackgroundColor(colors[position % colors.length]);
        }

        holder.itemView.setOnClickListener(v -> listener.onProfileClick(user));
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, initial;
        android.widget.ImageView profileImage;
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.profileName);
            initial = itemView.findViewById(R.id.profileInitial);
            profileImage = itemView.findViewById(R.id.profileImage);
        }
    }
}
