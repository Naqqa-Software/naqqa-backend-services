package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ExplainTest {

    @Test
    public void explainTreeForBooleanQuerySumsMatchedClauses() throws Exception {
        String[] docs = {
            "apple banana",
            "apple only"
        };
        TestSegments.TextField field = TestSegments.buildTextField(2, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(2)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "apple")), BooleanQuery.Occur.MUST)
            .add(new TermQuery(new Term("text", "banana")), BooleanQuery.Occur.SHOULD)
            .build();

        Explanation explanation = searcher.explain(query, 0);
        assertTrue(explanation.isMatch());
        assertEquals(2, explanation.details().size());

        float sum = 0f;
        for (Explanation child : explanation.details()) {
            assertTrue(child.isMatch());
            sum += child.value();
        }
        assertEquals(sum, explanation.value(), 1e-6);

        Explanation explanationDoc1 = searcher.explain(query, 1);
        assertTrue(explanationDoc1.isMatch());
        assertEquals(2, explanationDoc1.details().size());
        assertEquals(false, explanationDoc1.details().get(1).isMatch());
        assertEquals(explanationDoc1.details().get(0).value(), explanationDoc1.value(), 1e-6);
    }

    @Test
    public void explainNoMatchWhenMustNotClauseMatches() throws Exception {
        String[] docs = {"apple banana"};
        TestSegments.TextField field = TestSegments.buildTextField(1, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(1)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "apple")), BooleanQuery.Occur.MUST)
            .add(new TermQuery(new Term("text", "banana")), BooleanQuery.Occur.MUST_NOT)
            .build();
        Explanation explanation = searcher.explain(query, 0);
        assertTrue(!explanation.isMatch());
    }
}
