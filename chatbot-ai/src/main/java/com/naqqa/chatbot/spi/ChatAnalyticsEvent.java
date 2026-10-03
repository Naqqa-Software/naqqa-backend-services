package com.naqqa.chatbot.spi;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record ChatAnalyticsEvent(String name, Instant ts, String conversationId, String vid, String sid,
                                 String lang, String pagePath, Map<String, Object> props) {

    public ChatAnalyticsEvent {
        props = props == null ? Map.of() : new LinkedHashMap<>(props);
    }
}
