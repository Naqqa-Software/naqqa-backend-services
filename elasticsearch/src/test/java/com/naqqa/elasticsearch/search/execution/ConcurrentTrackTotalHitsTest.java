package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ConcurrentTrackTotalHitsTest {

    @Test
    public void thresholdRelationMatchesSequentialAcrossSeeds() throws Exception {
        for (long seed = 1; seed <= 5; seed++) {
            Random rnd = new Random(seed + 500);
            int segments = 8 + rnd.nextInt(6);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = new IndexSearcher(readers);
            IndexSearcher par = new IndexSearcher(readers, IndexSearcher.newVirtualThreadExecutor());
            Query query = new TermQuery(new Term("text", "needle"));

            for (int threshold : new int[] {1, 5, 20, 1000}) {
                TopDocs expected = seq.search(query, 5, threshold);
                TopDocs actual = par.search(query, 5, threshold);
                assertEquals(expected.totalHits().value(), actual.totalHits().value());
                assertEquals(expected.totalHits().relation(), actual.totalHits().relation());
                assertEquals(expected.scoreDocs().length, actual.scoreDocs().length);
            }

            TopDocs accurateExpected = seq.search(query, 5, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
            TopDocs accurateActual = par.search(query, 5, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
            assertEquals(accurateExpected.totalHits().value(), accurateActual.totalHits().value());
            assertEquals(TotalHits.Relation.EQUAL_TO, accurateActual.totalHits().relation());
        }
    }

    @Test
    public void combinedHitsAcrossSlicesTriggerLowerBoundEvenWhenNoSingleSliceExceedsThreshold() throws Exception {
        Random rnd = new Random(4242L);
        int segments = 10;
        int perSegment = 40;
        String[] docs = new String[perSegment];
        for (int d = 0; d < perSegment; d++) {
            docs[d] = "needle hay";
        }
        java.util.List<LeafReader> readers = new java.util.ArrayList<>();
        for (int s = 0; s < segments; s++) {
            TestSegments.TextField field = TestSegments.buildTextField(perSegment, docs);
            readers.add(SimpleLeafReader.builder(perSegment)
                .field("text", field.fieldInfo)
                .terms("text", field.terms, field.docCount)
                .norms("text", field.norms)
                .build());
        }
        IndexSearcher par = new IndexSearcher(readers, IndexSearcher.newVirtualThreadExecutor());
        assertTrue(par.slices().size() > 1);
        int perSliceDocs = par.slices().get(0).totalMaxDoc();
        assertTrue(perSliceDocs < segments * perSegment);
        Query query = new TermQuery(new Term("text", "needle"));
        int threshold = perSliceDocs + (segments * perSegment - perSliceDocs) / 2;
        TopDocs topDocs = par.search(query, 5, threshold);
        assertEquals((long) (segments * perSegment), topDocs.totalHits().value());
        assertEquals(TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO, topDocs.totalHits().relation());
    }

    @Test
    public void countMatchesSequentialAcrossSeeds() throws Exception {
        for (long seed = 1; seed <= 5; seed++) {
            Random rnd = new Random(seed + 600);
            int segments = 8 + rnd.nextInt(6);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = new IndexSearcher(readers);
            Executor executor = IndexSearcher.newVirtualThreadExecutor();
            IndexSearcher par = new IndexSearcher(readers, executor);
            Query query = new TermQuery(new Term("text", "alpha"));
            int expected = seq.count(query);
            int actual = par.count(query);
            assertEquals(expected, actual);
        }
    }
}
