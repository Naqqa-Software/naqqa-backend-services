package com.naqqa.chatbot.service;

import com.naqqa.chatbot.entities.ChatConversationEntity;
import com.naqqa.chatbot.spi.ChatAnalyticsEvent;
import com.naqqa.chatbot.spi.ChatAnalyticsSink;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Map;

@Slf4j
public class ChatAnalyticsEmitter {

    public static final ChatAnalyticsEmitter NONE = new ChatAnalyticsEmitter(ChatAnalyticsSink.NONE);

    private final ChatAnalyticsSink sink;

    public ChatAnalyticsEmitter(ChatAnalyticsSink sink) {
        this.sink = sink == null ? ChatAnalyticsSink.NONE : sink;
    }

    public void emit(String name, ChatConversationEntity conversation, Map<String, Object> props) {
        if (conversation == null) {
            emit(name, null, null, null, null, null, props);
            return;
        }
        emit(name, conversation.getId(), conversation.getAnalyticsVid(), conversation.getAnalyticsSid(),
                conversation.getLang(), conversation.getPagePath(), props);
    }

    public void emit(String name, String conversationId, String vid, String sid, String lang, String pagePath,
                     Map<String, Object> props) {
        try {
            sink.record(new ChatAnalyticsEvent(name, Instant.now(), conversationId, vid, sid, lang, pagePath, props));
        } catch (RuntimeException e) {
            log.debug("Chat analytics sink failed for {}: {}", name, e.getMessage());
        }
    }
}
