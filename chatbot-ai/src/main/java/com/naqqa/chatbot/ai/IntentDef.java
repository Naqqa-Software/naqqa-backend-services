package com.naqqa.chatbot.ai;

import java.util.List;

public record IntentDef(String id, String code, Role role, boolean browsable, boolean yieldsToCategory,
                        boolean yieldsToPrice, String template, String escalateTemplate, String knowledgeSource,
                        String resultsTemplate, String emptyTemplate, String emptyQueryTemplate,
                        String emptyResultsTemplate, String disabledTemplate, String emptyQuickReply,
                        List<String> quickReplies, List<String> quickRepliesWithResults,
                        IntentCatalog.TypeRules types, List<String> taxonomies, int maxItems) {

    public enum Role {
        GREETING, SEARCH, CATALOG, COMPANY, CATEGORY, CATEGORY_LIST, LOCATION, PAGE, KNOWLEDGE, PARTNER, CONTACT,
        OFF_TOPIC, INJECTION
    }

    public String key() {
        return code;
    }

    public boolean isCatalog() {
        return role == Role.SEARCH || role == Role.CATALOG || role == Role.COMPANY || role == Role.CATEGORY
                || role == Role.LOCATION;
    }

    public boolean isKnowledge() {
        return role == Role.KNOWLEDGE || role == Role.PARTNER;
    }

    public boolean is(Role r) {
        return role == r;
    }
}
