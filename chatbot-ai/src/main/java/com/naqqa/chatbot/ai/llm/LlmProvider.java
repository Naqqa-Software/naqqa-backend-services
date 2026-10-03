package com.naqqa.chatbot.ai.llm;

public interface LlmProvider {

    default boolean isEnabled() {
        return true;
    }

    boolean isAvailable();

    LlmResult generate(LlmRequest request);
}
