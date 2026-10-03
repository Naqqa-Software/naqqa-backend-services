package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.ai.knowledge.KnowledgeHit;

import java.util.List;

public interface ChatKnowledgeSearcher {

    List<KnowledgeHit> search(String terms, String lang, String preferredKey, int limit);
}
