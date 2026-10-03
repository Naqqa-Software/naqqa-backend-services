package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.ai.safety.AbuseGuard;
import com.naqqa.chatbot.ai.safety.ChatSafety;
import com.naqqa.chatbot.ai.safety.CrisisGuard;
import com.naqqa.chatbot.ai.safety.SafetyPacks;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.i18n.ChatResources;
import com.naqqa.chatbot.spi.ChatEntityResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatTestSupport {

    public static final List<String> LANGS = List.of("ro", "ru");
    public static final List<String> INTERNAL = List.of(
            "/promotions", "/products", "/booklets", "/offers", "/blogs", "/raffles", "/company", "/pages/",
            "/cum-functioneaza", "/parteneri", "/toti-partenerii", "/contact", "/accesibilitate",
            "/search-results", "/map", "/auth/login", "/auth/register", "/auth/register-partner",
            "/auth/forgot-password");
    public static final List<String> DOMAINS = List.of("omy.md", "www.omy.md");
    public static final ChatResources RESOURCES = ChatResources.defaults();
    public static final ChatLanguages LANGUAGES = new ChatLanguages(LANGS, placeholders(), RESOURCES);
    public static final ChatLanguages ALL_LANGUAGES = new ChatLanguages(List.of("ro", "ru", "en"), placeholders(), RESOURCES);
    public static final IntentCatalog CATALOG = IntentCatalog.load(RESOURCES);

    public static final List<com.naqqa.chatbot.ai.retrieval.ChatItemType> TYPES = List.of(
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("PROMOTION", 1.0, 0, true),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("OFFER", 0.95, 1, false),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("PRODUCT", 0.9, 2, true),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("BOOKLET", 0.85, 3, false),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("RAFFLE", 0.8, 4, false),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("BLOG", 0.75, 5, false),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("RECIPE", 0.72, 6, false),
            new com.naqqa.chatbot.ai.retrieval.ChatItemType("COMPANY", 0.7, 7, false));

    public static com.naqqa.chatbot.ai.retrieval.ChatItemType type(String key) {
        for (com.naqqa.chatbot.ai.retrieval.ChatItemType t : TYPES) {
            if (t.key().equals(key)) {
                return t;
            }
        }
        return null;
    }

    public static com.naqqa.chatbot.ai.retrieval.Ranker ranker() {
        return new com.naqqa.chatbot.ai.retrieval.Ranker(ChatTestSupport::type);
    }

    private ChatTestSupport() {
    }

    public static Map<String, String> placeholders() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("brand", "OMY");
        p.put("botName", "OMY Chat");
        p.put("contactEmail", "contact@omy.md");
        p.put("contactPhone", "+373 22 000 111");
        return p;
    }

    public static InputGuard inputGuard() {
        return new InputGuard(LANGUAGES, DOMAINS);
    }

    public static OutputGuard outputGuard() {
        return new OutputGuard(INTERNAL, DOMAINS, List.of("contact@omy.md", "+373 22 000 111"));
    }

    public static IntentRouter router(ChatEntityResolver directory) {
        return new IntentRouter(LANGUAGES, CATALOG, directory);
    }

    public static ChatSafety safety() {
        Map<String, com.fasterxml.jackson.databind.JsonNode> packs = SafetyPacks.load(RESOURCES, LANGS);
        Map<String, String> helplines = new LinkedHashMap<>();
        helplines.put("ro", "Telefonul de Încredere pentru Copii și Adolescenți 116 111");
        helplines.put("ru", "Телефоном доверия для детей и подростков 116 111");
        return new ChatSafety(new CrisisGuard(packs), new AbuseGuard(packs), LANGUAGES, "112", helplines);
    }

    public static com.naqqa.chatbot.ai.safety.TopicGuard topics() {
        return new com.naqqa.chatbot.ai.safety.TopicGuard(SafetyPacks.load(RESOURCES, LANGS));
    }

    public static String id(IntentResult r) {
        return r == null || r.intent() == null ? null : r.intent().id();
    }
}
