package com.example.stockit;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.stockit.controller.ChatAdapter;
import com.example.stockit.controller.MainController;
import com.example.stockit.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public class AIChatActivity extends AppCompatActivity {
    private ChatAdapter adapter;
    private List<ChatMessage> messages = new ArrayList<>();
    private MainController controller;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat); // On reutilise le layout existant

        controller = MainController.getInstance(this);
        
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.chatToolbar);
        toolbar.setTitle(getString(R.string.ai_chat_title));
        setSupportActionBar(toolbar);

        RecyclerView recyclerView = findViewById(R.id.chatRecyclerView);
        EditText input = findViewById(R.id.chatInput);
        ImageButton btnSend = findViewById(R.id.btnSendMessage);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);

        adapter = new ChatAdapter(messages);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        addMessage(getString(R.string.ai_name), getString(R.string.ai_welcome_msg));

        btnSend.setOnClickListener(v -> {
            String question = input.getText().toString().trim();
            if (!question.isEmpty()) {
                addMessage(getString(R.string.user_name_me), question);
                input.setText("");
                
                controller.askAssistant(question, response -> {
                    addMessage(getString(R.string.ai_name), response);
                });
            }
        });
    }

    private void addMessage(String sender, String text) {
        messages.add(new ChatMessage(sender, sender, text, System.currentTimeMillis()));
        adapter.notifyItemInserted(messages.size() - 1);
        RecyclerView rv = findViewById(R.id.chatRecyclerView);
        rv.scrollToPosition(messages.size() - 1);
    }
}
