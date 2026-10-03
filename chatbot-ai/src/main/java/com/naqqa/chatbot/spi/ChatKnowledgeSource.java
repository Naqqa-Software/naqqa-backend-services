package com.naqqa.chatbot.spi;

import com.naqqa.chatbot.ai.knowledge.KnowledgeDocument;

import java.util.List;

public interface ChatKnowledgeSource {

    List<KnowledgeDocument> documents(List<String> languages);
}
