package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;

public interface ScoreFunction {

    double score(int docId, float subQueryScore) throws IOException;

    default Explanation explain(int docId, float subQueryScore, Explanation subExplanation) throws IOException {
        double value = score(docId, subQueryScore);
        return Explanation.match((float) value, "function score, computed for doc " + docId, subExplanation);
    }
}
