package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class TopScoreDocCollectorTest {

    private static IndexSearcher buildSearcher(int maxDoc, int matchEveryNth) throws Exception {
        String[] docs = new String[maxDoc];
        for (int d = 0; d < maxDoc; d++) {
            docs[d] = d % matchEveryNth == 0 ? "needle hay" : "hay hay";
        }
        TestSegments.TextField field = TestSegments.buildTextField(maxDoc, docs);
        SimpleLeafReader reader = SimpleLeafReader.builder(maxDoc)
            .field("text", field.fieldInfo)
            .terms("text", field.terms, field.docCount)
            .norms("text", field.norms)
            .build();
        return new IndexSearcher(List.of(reader));
    }

    @Test
    public void totalHitsIsExactWhenBelowThreshold() throws Exception {
        IndexSearcher searcher = buildSearcher(50, 10);
        TermQuery query = new TermQuery(new Term("text", "needle"));
        TopDocs topDocs = searcher.search(query, 3, 100);
        assertEquals(5L, topDocs.totalHits().value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocs.totalHits().relation());
    }

    @Test
    public void totalHitsBecomesLowerBoundOnceThresholdExceeded() throws Exception {
        IndexSearcher searcher = buildSearcher(100, 2);
        TermQuery query = new TermQuery(new Term("text", "needle"));
        TopDocs topDocs = searcher.search(query, 3, 10);
        assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, topDocs.totalHits().relation());
        assertTrue(topDocs.totalHits().value() >= 10);
        assertEquals(3, topDocs.scoreDocs().length);
    }

    @Test
    public void trackTotalHitsAccurateAlwaysReportsExactCount() throws Exception {
        IndexSearcher searcher = buildSearcher(100, 2);
        TermQuery query = new TermQuery(new Term("text", "needle"));
        TopDocs topDocs = searcher.search(query, 3, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
        assertEquals(50L, topDocs.totalHits().value());
        assertEquals(TotalHits.Relation.EQUAL_TO, topDocs.totalHits().relation());
    }
}
