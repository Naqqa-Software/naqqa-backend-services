package com.naqqa.elasticsearch.search.similarity;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class BM25SimilarityTest {

    private static double expectedBm25(double idf, int freq, int dl, double avgdl, double k1, double b) {
        double denom = freq + k1 * (1 - b + b * dl / avgdl);
        return idf * (freq * (k1 + 1)) / denom;
    }

    private static double expectedIdf(long docFreq, long docCount) {
        return Math.log(1.0 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
    }

    @Test
    public void bm25ScoresMatchHandComputedFormula() throws Exception {
        String[] docs = {
            "the cat sat on the mat",
            "the dog sat",
            "cat cat cat"
        };
        TestSegments.TextField field = TestSegments.buildTextField(3, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(3)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader), new BM25Similarity(1.2f, 0.75f));

        double avgdl = 12.0 / 3.0;
        double idf = expectedIdf(2, 3);

        TermQuery query = new TermQuery(new Term("text", "cat"));
        Weight weight = query.createWeight(searcher, ScoreMode.COMPLETE, 1f);
        Explanation e0 = weight.explain(searcher.leafContexts().get(0), 0);
        Explanation e2 = weight.explain(searcher.leafContexts().get(0), 2);

        double expected0 = expectedBm25(idf, 1, 6, avgdl, 1.2, 0.75);
        double expected2 = expectedBm25(idf, 3, 3, avgdl, 1.2, 0.75);

        assertEquals(expected0, e0.value(), 1e-4);
        assertEquals(expected2, e2.value(), 1e-4);
        assertEquals(true, e2.value() > e0.value());
    }

    @Test
    public void idfDecreasesAsDocFreqIncreases() {
        double idfRare = expectedIdf(1, 100);
        double idfCommon = expectedIdf(50, 100);
        assertEquals(true, idfRare > idfCommon);
    }
}
