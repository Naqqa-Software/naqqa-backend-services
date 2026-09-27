package com.naqqa.elasticsearch.analysis.stem;

public interface Stemmer {

    boolean stem(StringBuilder word);

    default String stem(String word) {
        StringBuilder sb = new StringBuilder(word);
        stem(sb);
        return sb.toString();
    }
}
