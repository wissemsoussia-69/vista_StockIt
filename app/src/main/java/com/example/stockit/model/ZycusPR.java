package com.example.stockit.model;

public class ZycusPR {
    private String itemName;
    private int quantity;
    private String departmentCode; // ex: "IT-TUNIS-01"
    private String requester;      // ex: "Agent StockIT"

    public ZycusPR(String itemName, int quantity, String departmentCode, String requester) {
        this.itemName = itemName;
        this.quantity = quantity;
        this.departmentCode = departmentCode;
        this.requester = requester;
    }

    public static class Response {
        private String prNumber; // ex: "PR-2024-9874"
        private String status;   // ex: "PENDING_APPROVAL"

        public String getPrNumber() { return prNumber; }
        public String getStatus() { return status; }
    }
}
