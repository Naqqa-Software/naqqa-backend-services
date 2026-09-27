package com.naqqa.elasticsearch.search.similarity;

public record CollectionStatistics(String field, long maxDoc, long docCount, long sumDocFreq, long sumTotalTermFreq) {

    public double averageFieldLength() {
        if (docCount <= 0) {
            return 0.0;
        }
        return (double) sumTotalTermFreq / (double) docCount;
    }
}
