package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class DisjunctionMaxQueryTest {

    @Test
    public void picksMaxScorePlusTieBreakerFractionOfOthers() throws Exception {
        String[] docs = {
            "alpha alpha alpha beta",
            "beta beta beta alpha"
        };
        TestSegments.TextField field = TestSegments.buildTextField(2, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(2)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        TermQuery alphaQuery = new TermQuery(new Term("text", "alpha"));
        TermQuery betaQuery = new TermQuery(new Term("text", "beta"));
        float alphaScoreDoc0 = alphaQuery.createWeight(searcher, ScoreMode.COMPLETE, 1f)
            .explain(searcher.leafContexts().get(0), 0).value();
        float betaScoreDoc0 = betaQuery.createWeight(searcher, ScoreMode.COMPLETE, 1f)
            .explain(searcher.leafContexts().get(0), 0).value();

        float tieBreaker = 0.5f;
        DisjunctionMaxQuery dismax = new DisjunctionMaxQuery(List.of(alphaQuery, betaQuery), tieBreaker);
        Explanation explanation = dismax.createWeight(searcher, ScoreMode.COMPLETE, 1f)
            .explain(searcher.leafContexts().get(0), 0);

        float expected = Math.max(alphaScoreDoc0, betaScoreDoc0) + tieBreaker * Math.min(alphaScoreDoc0, betaScoreDoc0);
        assertEquals(expected, explanation.value(), 1e-5f);

        TopDocs topDocs = searcher.search(dismax, 2);
        assertEquals(2, topDocs.scoreDocs().length);
        for (ScoreDoc sd : topDocs.scoreDocs()) {
            assertEquals(true, sd.score > 0f);
        }
    }
}
