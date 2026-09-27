package com.naqqa.elasticsearch.search.suggest.term;

public record TermSuggestion(String text, int frequency, int editDistance, double score) {
}
