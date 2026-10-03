package com.naqqa.chatbot.service;

import java.util.List;
import java.util.stream.Collectors;

public final class ChatCsv {

    private ChatCsv() {
    }

    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (!text.isEmpty()) {
            char first = text.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                text = "'" + text;
            }
        }
        boolean quote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r") || text.contains(";");
        String escaped = text.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    public static String row(List<?> values) {
        return values.stream().map(ChatCsv::cell).collect(Collectors.joining(",")) + "\r\n";
    }
}
