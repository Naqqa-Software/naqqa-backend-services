package com.naqqa.chatbot.spi;

public interface ChatAnalyticsSink {

    ChatAnalyticsSink NONE = event -> { };

    void record(ChatAnalyticsEvent event);
}
