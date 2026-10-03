package com.naqqa.chatbot.ai;

public interface ChatAiEngine {

    AiReply reply(AiRequest request);

    AiReply suggest(AiRequest request);

    int reindexKnowledge();
}
