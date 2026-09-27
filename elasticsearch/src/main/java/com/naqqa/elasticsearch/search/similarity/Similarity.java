package com.naqqa.elasticsearch.search.similarity;

public interface Similarity {

    SimWeight computeWeight(CollectionStatistics collectionStats, TermStatistics... termStats);

    SimScorer simScorer(SimWeight weight);
}
