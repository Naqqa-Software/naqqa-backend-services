package com.naqqa.chatbot.ai.safety;

import com.naqqa.chatbot.i18n.ChatLanguages;

import java.util.List;
import java.util.Map;

public class ChatSafety {

    public static final String INTENT_CRISIS = "crisis";
    public static final String INTENT_DANGER = "danger";
    public static final String INTENT_ABUSE = "abuse";
    public static final String REASON_CRISIS = "CRISIS";
    public static final String REASON_DANGER = "DANGER";
    public static final String REASON_ABUSE = "ABUSE";

    public enum Kind { NONE, SELF_HARM, DANGER, PROFANITY, SEXUAL, HATE, SEXUAL_MINORS }

    public record Verdict(Kind kind, String intent, String template, String escalationReason, boolean offence,
                          boolean severe) {

        public boolean none() {
            return kind == Kind.NONE;
        }

        public boolean crisis() {
            return kind == Kind.SELF_HARM || kind == Kind.DANGER;
        }

        public boolean escalate() {
            return escalationReason != null;
        }
    }

    public static final Verdict NONE = new Verdict(Kind.NONE, null, null, null, false, false);

    private final CrisisGuard crisis;
    private final AbuseGuard abuse;
    private final ChatLanguages languages;
    private final String emergency;
    private final Map<String, String> helplines;

    public ChatSafety(CrisisGuard crisis, AbuseGuard abuse, ChatLanguages languages, String emergency,
                      Map<String, String> helplines) {
        this.crisis = crisis;
        this.abuse = abuse;
        this.languages = languages;
        this.emergency = emergency == null || emergency.isBlank() ? "112" : emergency.trim();
        this.helplines = helplines == null ? Map.of() : Map.copyOf(helplines);
    }

    public AbuseGuard abuse() {
        return abuse;
    }

    public CrisisGuard crisis() {
        return crisis;
    }

    public Verdict inspect(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        CrisisGuard.Level level = crisis == null ? CrisisGuard.Level.NONE : crisis.detect(text);
        if (level == CrisisGuard.Level.SELF_HARM) {
            return new Verdict(Kind.SELF_HARM, INTENT_CRISIS, "crisis.self_harm", REASON_CRISIS, false, true);
        }
        if (level == CrisisGuard.Level.DANGER) {
            return new Verdict(Kind.DANGER, INTENT_DANGER, "crisis.danger", REASON_DANGER, false, true);
        }
        AbuseGuard.Category category = abuse == null ? AbuseGuard.Category.NONE : abuse.inspect(text);
        return switch (category) {
            case SEXUAL_MINORS -> new Verdict(Kind.SEXUAL_MINORS, INTENT_ABUSE, "abuse.minors", REASON_ABUSE, true, true);
            case HATE -> new Verdict(Kind.HATE, INTENT_ABUSE, "abuse.hate", REASON_ABUSE, true, false);
            case SEXUAL -> new Verdict(Kind.SEXUAL, INTENT_ABUSE, "abuse.boundary", null, true, false);
            case PROFANITY -> new Verdict(Kind.PROFANITY, INTENT_ABUSE, "abuse.boundary", null, true, false);
            default -> NONE;
        };
    }

    public String reply(Verdict verdict, String lang) {
        if (verdict == null || verdict.none()) {
            return "";
        }
        String text = languages.template(verdict.template(), lang);
        String helpline = helplines.get(languages.normalize(lang));
        if (helpline == null) {
            helpline = helplines.get(languages.defaultLanguage());
        }
        String helplineText = helpline == null || helpline.isBlank() ? ""
                : languages.template("crisis.helpline", lang).replace("{helpline}", helpline.trim());
        return text.replace("{emergency}", emergency).replace("{helplines}", helplineText);
    }

    public List<String> quickReplies(Verdict verdict, String operatorQuickReply) {
        if (verdict != null && verdict.crisis() && operatorQuickReply != null) {
            return List.of(operatorQuickReply);
        }
        return List.of();
    }

    public boolean isSexualTitle(String title) {
        return abuse != null && abuse.isSexualTitle(title);
    }
}
