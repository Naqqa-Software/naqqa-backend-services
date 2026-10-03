package com.naqqa.chatbot.search;

import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.repository.ChatMessageRepository;
import com.naqqa.chatbot.service.ChatAdminService;
import com.naqqa.chatbot.spi.ChatMessageSearch;
import com.naqqa.chatbot.spi.ChatQueryExpander;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MongoChatMessageSearch implements ChatMessageSearch {

    private final ChatMessageRepository messages;
    private final ChatQueryExpander expander;

    public MongoChatMessageSearch(ChatMessageRepository messages, ChatQueryExpander expander) {
        this.messages = messages;
        this.expander = expander;
    }

    @Override
    public void index(ChatMessageEntity message) {
    }

    @Override
    public void delete(String conversationId) {
    }

    @Override
    public List<MessageSearchHit> search(String query, MessageSearchFilter filter) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        if (q.length() > 100) {
            q = q.substring(0, 100);
        }
        Pattern regex = Pattern.compile(ChatAdminService.escapeRegex(q), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Criteria> and = new ArrayList<>();
        and.add(Criteria.where("text").regex(regex));
        if (filter != null) {
            if (filter.conversationId() != null) {
                and.add(Criteria.where("conversationId").is(filter.conversationId()));
            }
            if (filter.conversationIds() != null) {
                and.add(Criteria.where("conversationId").in(filter.conversationIds()));
            }
            if (filter.from() != null) {
                and.add(Criteria.where("createdAt").gte(filter.from()));
            }
            if (filter.to() != null) {
                and.add(Criteria.where("createdAt").lt(filter.to()));
            }
            if (filter.lang() != null) {
                and.add(Criteria.where("lang").is(filter.lang()));
            }
        }
        int limit = filter == null || filter.limit() <= 0 ? 1000 : filter.limit();
        Query mongo = new Query(new Criteria().andOperator(and.toArray(new Criteria[0])))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(limit);
        List<String> variants = variants(q);
        List<MessageSearchHit> out = new ArrayList<>();
        for (ChatMessageEntity m : messages.find(mongo)) {
            out.add(hit(m, q, variants, 1.0));
        }
        return out;
    }

    public List<String> variants(String q) {
        if (expander == null) {
            return List.of();
        }
        try {
            List<String> v = expander.variants(q);
            return v == null ? List.of() : v;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static MessageSearchHit hit(ChatMessageEntity m, String q, List<String> variants, double score) {
        String text = m.getText() == null ? "" : com.naqqa.chatbot.ai.PiiMasker.mask(m.getText());
        return new MessageSearchHit(m.getId(), m.getConversationId(), m.getCreatedAt(),
                m.getSenderType() == null ? null : m.getSenderType().name(), text, ChatSnippets.ranges(text, q, variants), score);
    }
}
