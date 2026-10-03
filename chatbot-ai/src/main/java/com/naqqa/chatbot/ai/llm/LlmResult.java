package com.naqqa.chatbot.ai.llm;

import java.util.List;

public record LlmResult(String text, List<String> ids, double confidence, boolean escalate, int tokensIn,
                        int tokensOut) {
}
