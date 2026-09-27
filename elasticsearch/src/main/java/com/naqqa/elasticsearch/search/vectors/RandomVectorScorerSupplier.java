package com.naqqa.elasticsearch.search.vectors;

public interface RandomVectorScorerSupplier {

    RandomVectorScorer scorer(int ord);

    int maxOrd();
}
