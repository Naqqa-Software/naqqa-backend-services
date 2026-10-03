package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.ai.retrieval.Candidate;
import com.naqqa.chatbot.ai.retrieval.ChatItemType;
import com.naqqa.chatbot.ai.retrieval.CompanyRef;
import com.naqqa.chatbot.ai.retrieval.RetrievalPlan;

import java.util.List;

public interface ChatContentProvider {

    List<ChatItemType> itemTypes();

    List<Candidate> retrieve(RetrievalPlan plan);

    default boolean isAvailable(String type) {
        return true;
    }

    default String companyType() {
        return "COMPANY";
    }

    default String cacheToken(RetrievalPlan plan) {
        return "";
    }

    default Candidate companyCandidate(CompanyRef company) {
        return null;
    }

    default String imageUrl(String imageId) {
        return imageId;
    }
}
