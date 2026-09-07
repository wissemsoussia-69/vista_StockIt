package com.example.stockit;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.controller.ChatAdapter;
import com.example.stockit.controller.MainController;
import com.example.stockit.model.ChatMessage;
import com.example.stockit.model.User;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import java.util.ArrayList;

public class ChatActivity extends AppCompatActivity {
    private ChatAdapter adapter;
    private MainController controller;
    private int claimId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        claimId = getIntent().getIntExtra("CLAIM_ID", -1);
        String subject = getIntent().getStringExtra("CLAIM_SUBJECT");

        controller = new MainController(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.chatToolbar);
        if (subject != null) toolbar.setTitle("Détails: " + subject);

        RecyclerView recyclerView = findViewById(R.id.chatRecyclerView);
        EditText input = findViewById(R.id.chatInput);
        ImageButton btnSend = findViewById(R.id.btnSendMessage);

        adapter = new ChatAdapter(new ArrayList<>());
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        if (claimId != -1) loadMessages();

        btnSend.setOnClickListener(v -> {
            String text = input.getText().toString();
            if (!text.isEmpty() && claimId != -1) {
                controller.addMessage(claimId, text, () -> {
                    input.setText("");
                    loadMessages();
                });
            }
        });
    }

    private void loadMessages() {
        controller.getMessages(claimId, messages -> {
            adapter.setMessages(messages);
            RecyclerView rv = findViewById(R.id.chatRecyclerView);
            if (adapter.getItemCount() > 0) {
                rv.scrollToPosition(adapter.getItemCount() - 1);
            }
        });
    }
}
