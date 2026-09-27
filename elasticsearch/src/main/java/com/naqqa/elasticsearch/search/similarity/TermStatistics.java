package com.naqqa.elasticsearch.search.similarity;

public record TermStatistics(byte[] term, long docFreq, long totalTermFreq) {
}
