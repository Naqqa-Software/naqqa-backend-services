package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.ai.retrieval.PlaceRef;

import java.util.List;

public interface ChatUserContextProvider {

    ChatUserContextProvider NONE = new ChatUserContextProvider() {
    };

    record ShoppingList(String name, int total, int checked) {
    }

    default List<ShoppingList> lists(Long userId, String lang) {
        return List.of();
    }

    default PlaceRef place(Long userId) {
        return null;
    }
}
