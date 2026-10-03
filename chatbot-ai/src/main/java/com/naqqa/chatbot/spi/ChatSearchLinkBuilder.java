package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.ai.IntentResult;
import com.naqqa.chatbot.ai.retrieval.CategoryRef;

public interface ChatSearchLinkBuilder {

    ChatSearchLinkBuilder NONE = new ChatSearchLinkBuilder() {
    };

    default String seeAll(IntentResult intent) {
        return null;
    }

    default String categoryPath(CategoryRef category) {
        return null;
    }
}
