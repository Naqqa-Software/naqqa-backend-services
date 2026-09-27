package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class BoostingQueriesTest {

    private static IndexSearcher buildSearcher() throws Exception {
        String[] docs = {"apple banana", "apple only"};
        TestSegments.TextField field = TestSegments.buildTextField(2, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(2)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    @Test
    public void boostQueryMultipliesScoreByConstant() throws Exception {
        IndexSearcher searcher = buildSearcher();
        TermQuery base = new TermQuery(new Term("text", "apple"));
        float baseScore = searcher.explain(base, 0).value();

        BoostQuery boosted = new BoostQuery(base, 3.0f);
        float boostedScore = searcher.explain(boosted, 0).value();

        assertEquals(baseScore * 3.0f, boostedScore, 1e-5f);
    }

    @Test
    public void fieldLevelBoostMultipliesScore() throws Exception {
        IndexSearcher searcher = buildSearcher();
        TermQuery base = new TermQuery(new Term("text", "apple"));
        TermQuery fieldBoosted = new TermQuery(new Term("text", "apple"), 2.0f);

        float baseScore = searcher.explain(base, 0).value();
        float boostedScore = searcher.explain(fieldBoosted, 0).value();

        assertEquals(baseScore * 2.0f, boostedScore, 1e-5f);
    }

    @Test
    public void constantScoreQueryIgnoresUnderlyingScore() throws Exception {
        IndexSearcher searcher = buildSearcher();
        ConstantScoreQuery csq = new ConstantScoreQuery(new TermQuery(new Term("text", "apple")));
        Explanation e0 = searcher.explain(csq, 0);
        Explanation e1 = searcher.explain(csq, 1);
        assertTrue(e0.isMatch());
        assertTrue(e1.isMatch());
        assertEquals(1.0f, e0.value(), 1e-6f);
        assertEquals(1.0f, e1.value(), 1e-6f);
    }

    @Test
    public void functionScoreQueryMultipliesBySuppliedFunction() throws Exception {
        IndexSearcher searcher = buildSearcher();
        TermQuery base = new TermQuery(new Term("text", "apple"));
        float baseScore = searcher.explain(base, 0).value();

        ScoreFunction doubleIt = (docId, subQueryScore) -> 2.0;
        FunctionScoreQuery fsq = new FunctionScoreQuery(base, doubleIt, FunctionScoreQuery.CombineFunction.MULTIPLY);
        float combined = searcher.explain(fsq, 0).value();

        assertEquals(baseScore * 2.0f, combined, 1e-5f);
    }

    @Test
    public void matchAllAndMatchNoDocsBehaveAsExpected() throws Exception {
        IndexSearcher searcher = buildSearcher();
        assertEquals(2, searcher.count(new MatchAllDocsQuery()));
        assertEquals(0, searcher.count(new MatchNoDocsQuery()));
    }
}
