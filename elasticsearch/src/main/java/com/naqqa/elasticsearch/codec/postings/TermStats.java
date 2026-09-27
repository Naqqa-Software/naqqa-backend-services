package com.naqqa.elasticsearch.codec.postings;

public record TermStats(int docFreq, long totalTermFreq, long postingsFilePointer) {
}
