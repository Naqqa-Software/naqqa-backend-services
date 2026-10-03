package com.naqqa.chatbot.service;

import com.naqqa.chatbot.i18n.ChatLanguages;

public class ChatTexts {

    public static final String OPERATOR_JOINED = "OPERATOR_JOINED";
    public static final String AI_RESUMED = "AI_RESUMED";
    public static final String AI_PAUSED = "AI_PAUSED";
    public static final String CLOSED = "CLOSED";
    public static final String ESCALATED_WAITING = "ESCALATED_WAITING";
    public static final String ESCALATED_NO_OPERATOR = "ESCALATED_NO_OPERATOR";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";

    private final ChatLanguages languages;

    public ChatTexts(ChatLanguages languages) {
        this.languages = languages;
    }

    public ChatLanguages languages() {
        return languages;
    }

    public String get(String key, String lang) {
        return languages.template("system." + key, lang).replace("{schedule}", "");
    }

    public String operatorJoined(String name, String lang) {
        String fallback = languages.template("operator.default_name", lang);
        return languages.template("system." + OPERATOR_JOINED, lang)
                .replace("{name}", name == null || name.isBlank() ? (fallback.isBlank() ? "Operator" : fallback) : name);
    }

    public String noOperator(String schedule, String lang) {
        String suffix = schedule == null || schedule.isBlank() ? ""
                : languages.template("system.SCHEDULE", lang).replace("{schedule}", schedule);
        return languages.template("system." + ESCALATED_NO_OPERATOR, lang).replace("{schedule}", suffix);
    }

    public String defaultOperatorName(String lang) {
        String v = languages.template("operator.default_name", lang);
        return v.isBlank() ? "Operator" : v;
    }
}
