package com.naqqa.chatbot.ai.retrieval;

public record RankedItem(Candidate candidate, double score, double relevance, boolean sponsored) {
}
