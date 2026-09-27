package com.naqqa.elasticsearch.search.similarity;

public interface SimScorer {

    float score(float freq, long norm);

    Explanation explain(float freq, long norm, Explanation freqExplanation);
}
