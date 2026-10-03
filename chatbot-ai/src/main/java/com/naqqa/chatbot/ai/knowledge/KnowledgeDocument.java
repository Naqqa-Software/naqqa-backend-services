package com.naqqa.chatbot.ai.knowledge;

import java.util.List;

public record KnowledgeDocument(String sourceType, String sourceKey, String lang, String title, String path,
                                List<KnowledgeChunker.Section> sections, boolean html) {

    public static KnowledgeDocument html(String sourceType, String sourceKey, String lang, String title, String path,
                                         String html) {
        return new KnowledgeDocument(sourceType, sourceKey, lang, title, path,
                List.of(new KnowledgeChunker.Section(null, html)), true);
    }
}
