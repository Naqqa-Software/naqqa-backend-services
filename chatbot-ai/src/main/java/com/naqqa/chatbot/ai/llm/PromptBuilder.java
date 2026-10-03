package com.naqqa.chatbot.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.naqqa.chatbot.ai.AiTurn;
import com.naqqa.chatbot.ai.InputGuard;
import com.naqqa.chatbot.ai.knowledge.KnowledgeHit;
import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.RankedItem;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.spi.ChatEntityResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PromptBuilder {

    public static final int MAX_HISTORY = 6;
    public static final int MAX_ITEMS = 8;
    public static final int MAX_TOKENS = 300;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatEntityResolver directory;
    private final ChatLanguages languages;
    private final InputGuard guard;
    private final String companyType;

    public PromptBuilder(ChatEntityResolver directory, ChatLanguages languages, InputGuard guard, String companyType) {
        this.directory = directory == null ? ChatEntityResolver.NONE : directory;
        this.languages = languages;
        this.guard = guard;
        this.companyType = companyType == null ? "COMPANY" : companyType;
    }

    public String systemPrompt(String lang, boolean operatorDraft) {
        String base = languages.systemPrompt(lang);
        if (base == null || base.isBlank()) {
            base = languages.template("llm.fallback_prompt", lang);
        }
        if (!operatorDraft) {
            return base;
        }
        return base + languages.template("llm.operator_draft", lang);
    }

    public LlmRequest build(String lang, String userText, String intent, List<AiTurn> history, List<RankedItem> items,
                            List<KnowledgeHit> knowledge, boolean operatorDraft) {
        List<LlmMessage> messages = new ArrayList<>();
        List<AiTurn> turns = history == null ? List.of() : new ArrayList<>(history);
        if (!turns.isEmpty()) {
            AiTurn last = turns.get(turns.size() - 1);
            if ("user".equals(last.role()) && last.text() != null && last.text().trim().equals(userText == null ? "" : userText.trim())) {
                turns.remove(turns.size() - 1);
            }
        }
        if (turns.size() > MAX_HISTORY) {
            turns = turns.subList(turns.size() - MAX_HISTORY, turns.size());
        }
        for (AiTurn turn : turns) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            String text = clip(turn.text(), 500);
            if ("user".equals(turn.role())) {
                messages.add(new LlmMessage("user", "<user_message>" + escape(text) + "</user_message>"));
            } else if (!messages.isEmpty()) {
                String prefix = "operator".equals(turn.role()) ? "[operator] " : "";
                messages.add(new LlmMessage("assistant", prefix + escape(text)));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<context>{\"lang\":\"").append(lang).append("\",\"intent\":\"").append(intent)
                .append("\",\"mode\":\"").append(operatorDraft ? "operator_draft" : "visitor").append("\"}</context>\n");
        sb.append("<items>").append(itemsJson(items, lang)).append("</items>\n");
        sb.append("<knowledge>").append(knowledgeJson(knowledge)).append("</knowledge>\n");
        sb.append("<user_message>").append(escape(clip(userText == null ? "" : userText, 500))).append("</user_message>");
        messages.add(new LlmMessage("user", sb.toString()));
        return new LlmRequest(systemPrompt(lang, operatorDraft), messages, MAX_TOKENS);
    }

    public String itemsJson(List<RankedItem> items, String lang) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items != null) {
            for (RankedItem item : items) {
                if (out.size() >= MAX_ITEMS) {
                    break;
                }
                Candidate c = item.candidate();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.key());
                m.put("type", c.type());
                m.put("title", safeTitle(c.title(lang)));
                CompanyRef company = null;
                try {
                    company = c.companyId() == null ? null : directory.company(c.companyId());
                } catch (RuntimeException ignored) {
                }
                if (company != null && !companyType.equals(c.type())) {
                    m.put("company", safeTitle(company.name()));
                }
                if (c.price() != null) {
                    m.put("price", c.price());
                }
                if (c.originalPrice() != null) {
                    m.put("oldPrice", c.originalPrice());
                }
                if (c.discount() != null) {
                    m.put("discountPercent", c.discount());
                }
                if (c.validTo() != null) {
                    m.put("validTo", c.validTo().toString());
                }
                if (item.sponsored()) {
                    m.put("sponsored", true);
                }
                out.add(m);
            }
        }
        try {
            return escapeData(mapper.writeValueAsString(out));
        } catch (Exception e) {
            return "[]";
        }
    }

    public String knowledgeJson(List<KnowledgeHit> hits) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (hits != null) {
            for (KnowledgeHit hit : hits) {
                if (out.size() >= 3) {
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", safeTitle(hit.title()));
                m.put("text", clip(hit.text(), 700));
                out.add(m);
            }
        }
        try {
            return escapeData(mapper.writeValueAsString(out));
        } catch (Exception e) {
            return "[]";
        }
    }

    private String safeTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = clip(title, 120);
        return guard == null || guard.injectionReason(t) == null ? t : "-";
    }

    static String clip(String value, int max) {
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max);
    }

    static String escape(String value) {
        return value.replace("<", "‹").replace(">", "›");
    }

    private static String escapeData(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e");
    }
}
