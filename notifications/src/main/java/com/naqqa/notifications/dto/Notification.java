package com.naqqa.notifications.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record Notification(String id,
                           String type,
                           NotificationCategory category,
                           boolean seen,
                           String createdAt,
                           String actor,
                           String action,
                           String text,
                           String route,
                           Map<String, Object> extra) {

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("type", type);
        out.put("category", category.name());
        out.put("seen", seen);
        out.put("createdAt", createdAt);
        out.put("actor", actor);
        out.put("action", action);
        out.put("text", text);
        out.put("route", route);
        if (extra != null) {
            out.putAll(extra);
        }
        return out;
    }

    public Notification withSeen(boolean value) {
        return new Notification(id, type, category, value, createdAt, actor, action, text, route, extra);
    }
}
