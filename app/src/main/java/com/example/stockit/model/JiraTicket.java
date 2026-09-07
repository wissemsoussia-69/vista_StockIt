package com.example.stockit.model;

import java.io.Serializable;

public class JiraTicket implements Serializable {
    public String key;         // e.g. "ETXTUN-42"
    public String summary;
    public String status;      // "Backlog" / "In Progress" / ...
    public String priority;    // "Highest" / "Medium" / "J3: Near Term Desirable" ...
    public String dueDate;     // ISO "2026-07-20" or null
    public String description; // plain text extracted from ADF
    public String assignee;    // display name
    public String reporter;    // display name

    public JiraTicket() {}

    public String toShort() {
        StringBuilder sb = new StringBuilder(key);
        if (status != null)   sb.append("  |  ").append(status);
        if (priority != null) sb.append("  |  ").append(priority);
        if (dueDate != null)  sb.append("  |  due ").append(dueDate);
        return sb.toString();
    }

    public int priorityWeight() {
        if (priority == null) return 0;
        String p = priority.toLowerCase();
        if (p.contains("highest") || p.contains("j1") || p.contains("critique")) return 5;
        if (p.contains("high")    || p.contains("j2") || p.contains("haute"))    return 4;
        if (p.contains("medium")  || p.contains("j3") || p.contains("moyenne")) return 3;
        if (p.contains("low")     || p.contains("j4") || p.contains("basse"))    return 2;
        if (p.contains("lowest")  || p.contains("j5"))                            return 1;
        return 3;
    }
}
