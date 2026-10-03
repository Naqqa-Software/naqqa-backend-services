package com.naqqa.chatbot.service;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.text.Normalizer;
import java.util.Locale;

public class ChatEscalation {

    public static final String QUICK_REPLY_OPERATOR = "talk_to_operator";
    public static final int LOW_CONFIDENCE_LIMIT = 2;

    private final ChatLanguages languages;
    private final String operatorQuickReply;

    public ChatEscalation(ChatLanguages languages, String operatorQuickReply) {
        this.languages = languages;
        this.operatorQuickReply = operatorQuickReply == null ? QUICK_REPLY_OPERATOR : operatorQuickReply;
    }

    public String operatorQuickReply() {
        return operatorQuickReply;
    }

    public boolean isHumanRequest(String text, String quickReply) {
        if (operatorQuickReply.equals(quickReply)) {
            return true;
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return languages.isHumanRequest(normalized);
    }

    public static int nextLowConfidenceStreak(int current, double confidence, double minConfidence) {
        return confidence < minConfidence ? current + 1 : 0;
    }

    public static boolean shouldEscalate(boolean humanRequest, boolean aiEscalate, int lowConfidenceStreak) {
        return humanRequest || aiEscalate || lowConfidenceStreak >= LOW_CONFIDENCE_LIMIT;
    }
}
