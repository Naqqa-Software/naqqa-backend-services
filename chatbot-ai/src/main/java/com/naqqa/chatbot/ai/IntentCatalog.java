package com.naqqa.chatbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.naqqa.chatbot.i18n.ChatResources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IntentCatalog {

    public record QuickReplyDef(String key, String intent, boolean browse, boolean escalate) {
    }

    public record RelatedDef(List<String> types, List<String> triggerTypes, int max, double minRelevance) {

        public boolean enabled() {
            return max > 0 && !types.isEmpty() && !triggerTypes.isEmpty();
        }
    }

    public record TypeRules(List<String> defaults, List<String> withQuery, List<String> withPrice,
                            List<String> withDiscountSort, List<String> withCompany,
                            Map<String, List<String>> byTaxonomy) {

        public boolean isEmpty() {
            return defaults == null && withQuery == null && withPrice == null && withDiscountSort == null
                    && withCompany == null && (byTaxonomy == null || byTaxonomy.isEmpty());
        }
    }

    private final Map<String, IntentDef> intents = new LinkedHashMap<>();
    private final Map<String, IntentDef> byCode = new LinkedHashMap<>();
    private final Map<String, QuickReplyDef> quickReplies = new LinkedHashMap<>();
    private final List<String> knowledgeOrder;
    private final List<String> resolutionOrder;
    private final List<String> welcomeQuickReplies;
    private final List<String> defaultWithResults;
    private final List<String> defaultWithoutResults;
    private final Map<String, String> pages = new LinkedHashMap<>();
    private final String priceBrowseIntent;
    private final String defaultPage;
    private final TypeRules defaultTypes;
    private final RelatedDef related;

    public IntentCatalog(JsonNode root) {
        root.path("intents").fields().forEachRemaining(e -> {
            JsonNode n = e.getValue();
            if (!n.path("enabled").asBoolean(true)) {
                return;
            }
            IntentDef def = new IntentDef(e.getKey(), n.path("code").asText(e.getKey()),
                    IntentDef.Role.valueOf(n.path("role").asText("CATALOG")),
                    n.path("browsable").asBoolean(false), n.path("yieldsToCategory").asBoolean(false),
                    n.path("yieldsToPrice").asBoolean(false), text(n, "template"), text(n, "escalateTemplate"),
                    text(n, "knowledgeSource"), text(n, "resultsTemplate"), text(n, "emptyTemplate"),
                    text(n, "emptyQueryTemplate"), text(n, "emptyResultsTemplate"), text(n, "disabledTemplate"),
                    text(n, "emptyQuickReply"), list(n.path("quickReplies")), list(n.path("quickRepliesWithResults")),
                    types(n.path("types")), list(n.path("taxonomies")), n.path("maxItems").asInt(12));
            intents.put(def.id(), def);
            byCode.put(def.code(), def);
        });
        this.knowledgeOrder = existing(list(root.path("knowledgeOrder")));
        this.resolutionOrder = existing(list(root.path("resolutionOrder")));
        this.welcomeQuickReplies = orEmpty(list(root.path("welcomeQuickReplies")));
        this.defaultWithResults = orEmpty(list(root.path("defaultQuickReplies").path("withResults")));
        this.defaultWithoutResults = orEmpty(list(root.path("defaultQuickReplies").path("withoutResults")));
        root.path("quickReplies").fields().forEachRemaining(e -> quickReplies.put(e.getKey(),
                new QuickReplyDef(e.getKey(), e.getValue().path("intent").asText(""),
                        e.getValue().path("browse").asBoolean(false), e.getValue().path("escalate").asBoolean(false))));
        root.path("pages").fields().forEachRemaining(e -> pages.put(e.getKey(), e.getValue().asText()));
        this.priceBrowseIntent = text(root, "priceBrowseIntent");
        this.defaultPage = root.path("defaultPage").asText("faq");
        this.defaultTypes = types(root.path("defaultTypes"));
        JsonNode r = root.path("related");
        this.related = new RelatedDef(orEmpty(list(r.path("types"))), orEmpty(list(r.path("triggerTypes"))),
                Math.max(0, Math.min(6, r.path("max").asInt(3))), r.path("minRelevance").asDouble(0.35));
    }

    public static IntentCatalog load(ChatResources resources) {
        return new IntentCatalog(resources.json(ChatResources.ROOT + "intents.json"));
    }

    private List<String> existing(List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (intents.containsKey(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    static List<String> list(JsonNode n) {
        if (n == null || !n.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : n) {
            out.add(v.asText());
        }
        return List.copyOf(out);
    }

    private static TypeRules types(JsonNode n) {
        Map<String, List<String>> byTaxonomy = new LinkedHashMap<>();
        n.path("byTaxonomy").fields().forEachRemaining(e -> byTaxonomy.put(e.getKey(), list(e.getValue())));
        return new TypeRules(list(n.path("default")), list(n.path("withQuery")), list(n.path("withPrice")),
                list(n.path("withDiscountSort")), list(n.path("withCompany")), byTaxonomy);
    }

    public IntentDef get(String id) {
        return id == null ? null : intents.get(id);
    }

    public IntentDef byCode(String code) {
        return code == null ? null : byCode.get(code);
    }

    public IntentDef first(IntentDef.Role role) {
        for (IntentDef def : intents.values()) {
            if (def.role() == role) {
                return def;
            }
        }
        return null;
    }

    public List<IntentDef> all() {
        return List.copyOf(intents.values());
    }

    public List<String> knowledgeOrder() {
        return knowledgeOrder;
    }

    public List<String> resolutionOrder() {
        return resolutionOrder;
    }

    public QuickReplyDef quickReply(String key) {
        return key == null ? null : quickReplies.get(key);
    }

    public Map<String, QuickReplyDef> quickReplies() {
        return quickReplies;
    }

    public String escalateQuickReply() {
        for (QuickReplyDef q : quickReplies.values()) {
            if (q.escalate()) {
                return q.key();
            }
        }
        return null;
    }

    public List<String> welcomeQuickReplies() {
        return welcomeQuickReplies;
    }

    public Map<String, String> pages() {
        return pages;
    }

    public String pagePath(String slug) {
        String path = pages.get(slug);
        return path != null ? path : "/pages/" + slug;
    }

    public String defaultPage() {
        return defaultPage;
    }

    public IntentDef priceBrowseIntent() {
        return get(priceBrowseIntent);
    }

    public TypeRules defaultTypes() {
        return defaultTypes;
    }

    public RelatedDef related() {
        return related;
    }

    public List<String> quickRepliesFor(IntentDef intent, boolean hasResults) {
        if (intent != null) {
            if (hasResults && intent.quickRepliesWithResults() != null) {
                return intent.quickRepliesWithResults();
            }
            if (intent.quickReplies() != null) {
                return intent.quickReplies();
            }
        }
        return hasResults ? defaultWithResults : defaultWithoutResults;
    }
}
