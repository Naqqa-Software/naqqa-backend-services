package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.ai.retrieval.CategoryRef;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.PlaceRef;

import java.util.List;

public interface ChatEntityResolver {

    ChatEntityResolver NONE = new ChatEntityResolver() {
    };

    default List<CompanyRef> companies() {
        return List.of();
    }

    default List<CategoryRef> categories() {
        return List.of();
    }

    default CompanyRef company(Long id) {
        if (id == null) {
            return null;
        }
        for (CompanyRef c : companies()) {
            if (id.equals(c.id())) {
                return c;
            }
        }
        return null;
    }

    default List<PlaceRef> places() {
        return List.of();
    }

    default List<String> brands() {
        return List.of();
    }
}
