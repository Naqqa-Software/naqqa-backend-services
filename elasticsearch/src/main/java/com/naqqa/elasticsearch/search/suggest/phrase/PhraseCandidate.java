package com.naqqa.elasticsearch.search.suggest.phrase;

import java.util.List;

public record PhraseCandidate(List<String> words, double score) {

    public String text() {
        return String.join(" ", words);
    }
}
