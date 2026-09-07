package com.example.stockit.controller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.R;
import com.example.stockit.model.User;
import java.util.List;

public class UserAdapter extends RecyclerView.Adapter<UserAdapter.ViewHolder> {
    private List<User> users;
    private final OnShowListener showListener;
    private final OnDeleteListener deleteListener;

    public interface OnShowListener { void onShow(User u); }
    public interface OnDeleteListener { void onDelete(User u); }

    public UserAdapter(List<User> users, OnShowListener showListener, OnDeleteListener deleteListener) {
        this.users = users;
        this.showListener = showListener;
        this.deleteListener = deleteListener;
    }

    public void setUsers(List<User> users) {
        this.users = users;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.user_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        User user = users.get(position);
        holder.name.setText(user.getUsername() + " (Lvl " + user.getLevel() + ")");
        holder.email.setText(user.getPoints() + " Points");
        
        if (user.getUsername() != null && !user.getUsername().isEmpty()) {
            holder.initial.setText(user.getUsername().substring(0, 1).toUpperCase());
        }

        String role = user.getRole();
        Context ctx = holder.itemView.getContext();
        if ("ADMIN".equals(role)) {
            holder.roleBadge.setText(ctx.getString(R.string.role_admin));
            holder.roleBadge.getBackground().setTint(0xFFC0392B);
        } else {
            holder.roleBadge.setText(ctx.getString(R.string.role_employee));
            holder.roleBadge.getBackground().setTint(0xFF7F8C8D);
        }

        holder.itemView.setOnClickListener(v -> {
            if (showListener != null) showListener.onShow(user);
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (deleteListener != null) deleteListener.onDelete(user);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, email, roleBadge, initial;
        View avatarBg;

        public ViewHolder(View view) {
            super(view);
            name = view.findViewById(R.id.userName);
            email = view.findViewById(R.id.userEmail);
            roleBadge = view.findViewById(R.id.userRoleBadge);
            initial = view.findViewById(R.id.userInitial);
            avatarBg = view.findViewById(R.id.userAvatarBg);
        }
    }
}
