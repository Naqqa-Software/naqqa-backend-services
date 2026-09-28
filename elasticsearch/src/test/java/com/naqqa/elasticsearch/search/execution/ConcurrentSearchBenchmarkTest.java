package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Random;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ConcurrentSearchBenchmarkTest {

    @Test
    public void parallelSearchRunsWithoutErrorOnLargerCorpus() throws Exception {
        Random rnd = new Random(2024L);
        int segments = 32;
        List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
        long totalDocs = 0;
        for (LeafReader r : readers) {
            totalDocs += r.maxDoc();
        }
        assertTrue(totalDocs > 4_000);

        IndexSearcher seq = new IndexSearcher(readers);
        IndexSearcher par = new IndexSearcher(readers, IndexSearcher.newVirtualThreadExecutor());
        assertTrue(par.slices().size() > 1);

        Query query = new TermQuery(new Term("text", "needle"));

        long startSeq = System.nanoTime();
        TopDocs expected = seq.search(query, 20);
        long seqMillis = (System.nanoTime() - startSeq) / 1_000_000;

        long startPar = System.nanoTime();
        TopDocs actual = par.search(query, 20);
        long parMillis = (System.nanoTime() - startPar) / 1_000_000;

        assertEquals(expected.totalHits().value(), actual.totalHits().value());
        assertEquals(expected.totalHits().relation(), actual.totalHits().relation());
        assertEquals(expected.scoreDocs().length, actual.scoreDocs().length);
        for (int i = 0; i < expected.scoreDocs().length; i++) {
            assertEquals(expected.scoreDocs()[i].doc, actual.scoreDocs()[i].doc);
            assertEquals(expected.scoreDocs()[i].score, actual.scoreDocs()[i].score, 0.0);
        }
        assertTrue(seqMillis >= 0);
        assertTrue(parMillis >= 0);

        int seqCount = seq.count(query);
        int parCount = par.count(query);
        assertEquals(seqCount, parCount);
    }
}
