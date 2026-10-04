package com.naqqa.chatbot.ai;

public record AiTurn(String role, String text, String context) {

    public AiTurn(String role, String text) {
        this(role, text, null);
    }
}
