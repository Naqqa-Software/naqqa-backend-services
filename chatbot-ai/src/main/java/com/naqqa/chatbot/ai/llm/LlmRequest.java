package com.naqqa.chatbot.ai.llm;

import java.util.List;

public record LlmRequest(String system, List<LlmMessage> messages, int maxTokens) {
}
