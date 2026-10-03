package com.naqqa.chatbot.service;

import com.naqqa.chatbot.ai.AiReply;
import com.naqqa.chatbot.ai.TextNormalizer;
import com.naqqa.chatbot.dto.ChatDtos.PageDto;
import com.naqqa.chatbot.dto.ChatDtos.ReviewItemDto;
import com.naqqa.chatbot.dto.ChatDtos.SuggestionItemDto;
import com.naqqa.chatbot.dto.ChatDtos.SuggestionsDto;
import com.naqqa.chatbot.entities.ChatAuditAction;
import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.entities.ChatSenderType;
import com.naqqa.chatbot.i18n.ChatLanguages;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class ChatReviewService {

    public static final String TYPE_NO_RESULTS = "NO_RESULTS_QUERY";
    public static final String TYPE_INTENT_SYNONYM = "INTENT_SYNONYM";
    public static final String TYPE_KNOWLEDGE_GAP = "KNOWLEDGE_GAP";
    private static final String SUGGESTIONS_ID = "latest";
    private static final Set<String> UNKNOWN_INTENTS = Set.of("off_topic", "unknown", "error");

    private final ChatMessageRepository messages;
    private final ChatSettingsService settings;
    private final ChatAuditService audit;
    private final MongoTemplate mongoTemplate;
    private final String suggestionCollection;
    private final ChatLanguages languages;
    private final Set<String> knowledgeIntents;

    public ChatReviewService(ChatMessageRepository messages, ChatSettingsService settings, ChatAuditService audit,
                             MongoTemplate mongoTemplate, String suggestionCollection, ChatLanguages languages,
                             Set<String> knowledgeIntents) {
        this.messages = messages;
        this.settings = settings;
        this.audit = audit;
        this.mongoTemplate = mongoTemplate;
        this.suggestionCollection = suggestionCollection == null || suggestionCollection.isBlank() ? "chat_review_suggestion" : suggestionCollection;
        this.languages = languages;
        this.knowledgeIntents = knowledgeIntents == null ? Set.of() : Set.copyOf(knowledgeIntents);
    }

    public Query query(LocalDate from, LocalDate to, String flag, String route, String lang, boolean includeResolved) {
        ZoneId zone = ChatSchedule.zone(settings.get());
        List<Criteria> and = new ArrayList<>();
        and.add(Criteria.where("senderType").is(ChatSenderType.BOT));
        if (!includeResolved) {
            and.add(Criteria.where("needsReview").is(true));
        } else {
            and.add(new Criteria().orOperator(Criteria.where("needsReview").is(true), Criteria.where("reviewResolvedAt").ne(null)));
        }
        if (from != null) {
            and.add(Criteria.where("createdAt").gte(from.atStartOfDay(zone).toInstant()));
        }
        if (to != null) {
            and.add(Criteria.where("createdAt").lt(to.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        if (flag != null && !flag.isBlank()) {
            and.add(Criteria.where("qualityFlags").is(flag.trim().toUpperCase()));
        }
        if (route != null && !route.isBlank()) {
            and.add(Criteria.where("route").is(route.trim().toUpperCase()));
        }
        if (lang != null && !lang.isBlank()) {
            and.add(Criteria.where("lang").is(languages.normalize(lang)));
        }
        return new Query(new Criteria().andOperator(and.toArray(new Criteria[0])));
    }

    public PageDto<ReviewItemDto> list(LocalDate from, LocalDate to, String flag, String route, String lang, boolean includeResolved,
                                       int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Query query = query(from, to, flag, route, lang, includeResolved);
        long total = messages.count(query);
        query.with(Sort.by(Sort.Direction.DESC, "createdAt")).skip((long) p * s).limit(s);
        List<ReviewItemDto> items = messages.find(query).stream().map(ChatReviewService::item).toList();
        return new PageDto<>(items, total, (int) Math.ceil(total / (double) s), p);
    }

    public static ReviewItemDto item(ChatMessageEntity m) {
        return new ReviewItemDto(m.getId(), m.getConversationId(), m.getCreatedAt(), m.getLang(), m.getQuestion(), m.getText(),
                m.getRoute(), m.getIntent(), m.getConfidence(), m.getQualityFlags() == null ? List.of() : m.getQualityFlags(),
                m.getFeedback(), m.getFeedbackReason(), m.getReviewResolvedAt() != null, m.getReviewNote());
    }

    public ReviewItemDto resolve(ChatOperator operator, String messageId, String note) {
        ChatMessageEntity m = messages.findById(messageId)
                .filter(x -> x.getSenderType() == ChatSenderType.BOT)
                .orElseThrow(() -> new ChatException(HttpStatus.NOT_FOUND, ChatException.NOT_FOUND, "Message not found."));
        String value = note == null ? null : note.trim();
        if (value != null && value.length() > 1000) {
            value = value.substring(0, 1000);
        }
        m.setNeedsReview(false);
        m.setReviewResolvedAt(Instant.now());
        m.setReviewNote(value == null || value.isEmpty() ? null : value);
        m.setReviewedBy(operator == null ? null : operator.id());
        ChatMessageEntity saved = messages.save(m);
        audit.log(operator, ChatAuditAction.REVIEW_RESOLVE, m.getConversationId(), "message=" + messageId);
        return item(saved);
    }

    public SuggestionsDto suggestions() {
        try {
            Document doc = mongoTemplate.findById(SUGGESTIONS_ID, Document.class, suggestionCollection);
            if (doc != null) {
                return fromDocument(doc);
            }
        } catch (RuntimeException e) {
            log.debug("[chatbot] cannot read review suggestions: {}", e.getMessage());
        }
        return rebuild();
    }

    public SuggestionsDto rebuild() {
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        Query query = Query.query(Criteria.where("senderType").is(ChatSenderType.BOT).and("createdAt").gte(since)
                .and("qualityFlags").in(AiReply.FLAG_NO_RESULTS, AiReply.FLAG_LOW_CONFIDENCE, AiReply.FLAG_REPHRASED,
                        AiReply.FLAG_THUMBS_DOWN, AiReply.FLAG_OPERATOR_REQUESTED)).limit(5000);
        Map<String, Agg> groups = new LinkedHashMap<>();
        for (ChatMessageEntity m : messages.find(query)) {
            String question = m.getQuestion();
            if (question == null || question.isBlank() || m.getSafety() != null) {
                continue;
            }
            String normalized = String.join(" ", TextNormalizer.tokens(question));
            if (normalized.isBlank()) {
                continue;
            }
            List<String> flags = m.getQualityFlags() == null ? List.of() : m.getQualityFlags();
            String type;
            if (flags.contains(AiReply.FLAG_NO_RESULTS)) {
                type = TYPE_NO_RESULTS;
            } else if (m.getIntent() == null || UNKNOWN_INTENTS.contains(m.getIntent()) || flags.contains(AiReply.FLAG_LOW_CONFIDENCE)) {
                type = TYPE_INTENT_SYNONYM;
            } else if (knowledgeIntents.contains(m.getIntent())) {
                type = TYPE_KNOWLEDGE_GAP;
            } else {
                type = TYPE_INTENT_SYNONYM;
            }
            String key = type + "|" + m.getLang() + "|" + normalized;
            Agg agg = groups.computeIfAbsent(key, k -> new Agg(type, m.getLang(), normalized, m.getIntent()));
            agg.count++;
            if (agg.examples.size() < 3 && !agg.examples.contains(question)) {
                agg.examples.add(question.length() > 200 ? question.substring(0, 200) : question);
            }
        }
        List<SuggestionItemDto> items = groups.values().stream()
                .sorted((a, b) -> Long.compare(b.count, a.count))
                .limit(100)
                .map(a -> new SuggestionItemDto(a.type, a.lang, a.text, a.intent, a.count, List.copyOf(a.examples)))
                .toList();
        SuggestionsDto result = new SuggestionsDto(Instant.now(), items);
        try {
            mongoTemplate.save(toDocument(result), suggestionCollection);
        } catch (RuntimeException e) {
            log.debug("[chatbot] cannot store review suggestions: {}", e.getMessage());
        }
        return result;
    }

    private static final class Agg {
        private final String type;
        private final String lang;
        private final String text;
        private final String intent;
        private long count;
        private final List<String> examples = new ArrayList<>();

        private Agg(String type, String lang, String text, String intent) {
            this.type = type;
            this.lang = lang;
            this.text = text;
            this.intent = intent;
        }
    }

    private static Document toDocument(SuggestionsDto dto) {
        List<Document> items = new ArrayList<>();
        for (SuggestionItemDto i : dto.items()) {
            items.add(new Document("type", i.type()).append("lang", i.lang()).append("text", i.text())
                    .append("intent", i.intent()).append("count", i.count()).append("examples", i.examples()));
        }
        return new Document("_id", SUGGESTIONS_ID).append("generated_at", java.util.Date.from(dto.generatedAt())).append("items", items);
    }

    @SuppressWarnings("unchecked")
    private static SuggestionsDto fromDocument(Document doc) {
        List<SuggestionItemDto> items = new ArrayList<>();
        Object raw = doc.get("items");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Document d) {
                    Object ex = d.get("examples");
                    items.add(new SuggestionItemDto(d.getString("type"), d.getString("lang"), d.getString("text"),
                            d.getString("intent"), d.get("count") instanceof Number n ? n.longValue() : 0,
                            ex instanceof List<?> l ? (List<String>) l : List.of()));
                }
            }
        }
        Object at = doc.get("generated_at");
        Instant generated = at instanceof java.util.Date date ? date.toInstant() : null;
        return new SuggestionsDto(generated, items);
    }
}
