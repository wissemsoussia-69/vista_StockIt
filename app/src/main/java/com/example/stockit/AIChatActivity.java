package com.example.stockit;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TM-33 : captures d'écran / aperçu multitâche désactivés sur cet écran.
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_chat); // On réutilise le layout existant

        controller = new MainController(this);
        
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.chatToolbar);
        toolbar.setTitle(getString(R.string.ai_chat_title));
        setSupportActionBar(toolbar);

        RecyclerView recyclerView = findViewById(R.id.chatRecyclerView);
        EditText input = findViewById(R.id.chatInput);
        ImageButton btnSend = findViewById(R.id.btnSendMessage);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        adapter = new ChatAdapter(messages);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Message de bienvenue
        addMessage(getString(R.string.ai_name), getString(R.string.ai_welcome_msg));

        btnSend.setOnClickListener(v -> {
            String question = input.getText().toString().trim();
            if (!question.isEmpty()) {
                addMessage(getString(R.string.user_name_me), question);
                input.setText("");
                btnSend.setEnabled(false);
                btnSend.setAlpha(0.4f);
                addMessage(getString(R.string.ai_name), com.example.stockit.util.AppLanguage.L("… réflexion en cours"));
                final int typingIndex = messages.size() - 1;

                controller.askAssistant(question, response -> {
                    if (typingIndex < messages.size()) {
                        messages.remove(typingIndex);
                        adapter.notifyItemRemoved(typingIndex);
                    }
                    addMessage(getString(R.string.ai_name), response);
                    btnSend.setEnabled(true);
                    btnSend.setAlpha(1f);
                });
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void addMessage(String sender, String text) {
        messages.add(new ChatMessage(sender, sender, text, System.currentTimeMillis()));
        adapter.notifyItemInserted(messages.size() - 1);
        RecyclerView rv = findViewById(R.id.chatRecyclerView);
        rv.scrollToPosition(messages.size() - 1);
    }
}
