package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.entities.ChatMessageEntity;
import com.naqqa.chatbot.search.MessageSearchFilter;
import com.naqqa.chatbot.search.MessageSearchHit;

import java.time.Instant;
import java.util.List;

public interface ChatMessageSearch {

    void index(ChatMessageEntity message);

    void delete(String conversationId);

    default void deleteBefore(Instant before) {
    }

    List<MessageSearchHit> search(String query, MessageSearchFilter filter);
}
