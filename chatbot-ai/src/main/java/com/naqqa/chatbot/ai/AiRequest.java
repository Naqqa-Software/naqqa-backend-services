package com.naqqa.chatbot.ai;

import com.naqqa.chatbot.entities.ChatSettingsEntity;

import java.util.List;

public record AiRequest(String conversationId, String lang, String text, String quickReply, String pagePath,
                        List<AiTurn> history, ChatSettingsEntity settings) {
}
