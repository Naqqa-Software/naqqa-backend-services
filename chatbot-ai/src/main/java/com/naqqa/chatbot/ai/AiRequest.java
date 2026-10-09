package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.entities.ChatSettingsEntity;

import java.util.List;

public record AiRequest(String conversationId, String lang, String text, String quickReply, String pagePath,
                        List<AiTurn> history, ChatSettingsEntity settings, MemoryContext memory) {

    public AiRequest(String conversationId, String lang, String text, String quickReply, String pagePath,
                     List<AiTurn> history, ChatSettingsEntity settings) {
        this(conversationId, lang, text, quickReply, pagePath, history, settings, null);
    }

    public AiRequest withMemory(MemoryContext value) {
        return new AiRequest(conversationId, lang, text, quickReply, pagePath, history, settings, value);
    }
}
