package com.example.stockit.model;

public class ChatMessage {
    private String senderId;
    private String senderName;
    private String message;
    private long timestamp;

    public ChatMessage() {}

    public ChatMessage(String senderId, String senderName, String message, long timestamp) {
        this.senderId = senderId;
        this.senderName = senderName;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getSenderId() { return senderId; }
    public String getSenderName() { return senderName; }
    public String getMessage() { return message; }
    public long getTimestamp() { return timestamp; }
}
