package com.naqqa.elasticsearch.search.advanced.profile;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.SimpleLeafReader;
import com.naqqa.elasticsearch.search.execution.TestSegments;
import com.naqqa.elasticsearch.search.execution.TopScoreDocCollector;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ProfilerTest {

    @Test
    public void profilesBooleanQueryWithCorrectTreeShapeAndNonNegativeTimings() throws Exception {
        int maxDoc = 30;
        String[] docs = new String[maxDoc];
        for (int d = 0; d < maxDoc; d++) {
            docs[d] = d % 2 == 0 ? "needle hay" : "hay hay";
        }
        TestSegments.TextField field = TestSegments.buildTextField(maxDoc, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        IndexSearcher searcher = new IndexSearcher(List.of(reader));

        BooleanQuery query = BooleanQuery.builder()
            .add(new TermQuery(new Term("text", "needle")), BooleanQuery.Occur.SHOULD)
            .add(new TermQuery(new Term("text", "hay")), BooleanQuery.Occur.SHOULD)
            .build();

        TopScoreDocCollector collector = TopScoreDocCollector.create(10);
        ProfileResult result = Profiler.profile(searcher, query, collector);

        assertEquals("total", result.type());
        assertEquals(1, result.children().size());
        ProfileResult boolNode = result.children().get(0);
        assertEquals(2, boolNode.children().size());
        assertTrue(boolNode.timeInNanos() >= 0);
        for (ProfileResult child : boolNode.children()) {
            assertTrue(child.timeInNanos() >= 0);
            assertTrue(child.breakdown().get("next_doc") >= 0);
            assertTrue(child.breakdown().get("score") >= 0);
        }
        assertTrue(result.timeInNanos() >= boolNode.timeInNanos());

        java.util.Map<String, Object> rendered = result.toMap();
        assertEquals("total", rendered.get("type"));
        assertTrue(((List<?>) rendered.get("children")).size() == 1);
    }
}
