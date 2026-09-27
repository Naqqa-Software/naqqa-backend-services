package com.naqqa.elasticsearch.search.advanced.requests;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class RankEvalTest {

    @Test
    public void computesPrecisionDcgAndNdcgOnAKnownRelevanceSet() throws Exception {
        int maxDoc = 4;
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc).build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        Map<Integer, Integer> ratings = Map.of(0, 3, 1, 2, 2, 3, 3, 0);
        RankEval.Metrics metrics = RankEval.evaluate(searcher, new MatchAllDocsQuery(), ratings, 4);

        double log2 = Math.log(2);
        double expectedDcg = 3 / (Math.log(2) / log2)
            + 2 / (Math.log(3) / log2)
            + 3 / (Math.log(4) / log2)
            + 0 / (Math.log(5) / log2);
        double expectedIdcg = 3 / (Math.log(2) / log2)
            + 3 / (Math.log(3) / log2)
            + 2 / (Math.log(4) / log2)
            + 0 / (Math.log(5) / log2);
        double expectedNdcg = expectedDcg / expectedIdcg;

        assertEquals(0.75, metrics.precisionAtK(), 1e-9);
        assertEquals(1.0, metrics.reciprocalRank(), 1e-9);
        assertEquals(expectedDcg, metrics.dcg(), 1e-6);
        assertEquals(expectedIdcg, metrics.idcg(), 1e-6);
        assertEquals(expectedNdcg, metrics.ndcg(), 1e-6);
    }
}
