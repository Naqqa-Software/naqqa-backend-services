package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.PhraseQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ConcurrentSearchCorrectnessTest {

    private static final long[] SEEDS = {1L, 2L, 3L, 4L, 5L};
    private static final byte[] ALPHA = "alpha".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] BETA = "beta".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static void assertSameTopDocs(TopDocs expected, TopDocs actual) {
        assertEquals(expected.totalHits().value(), actual.totalHits().value());
        assertEquals(expected.totalHits().relation(), actual.totalHits().relation());
        assertEquals(expected.scoreDocs().length, actual.scoreDocs().length);
        for (int i = 0; i < expected.scoreDocs().length; i++) {
            assertEquals(expected.scoreDocs()[i].doc, actual.scoreDocs()[i].doc);
            assertEquals(expected.scoreDocs()[i].score, actual.scoreDocs()[i].score, 0.0);
        }
    }

    private static IndexSearcher sequentialSearcher(List<LeafReader> readers) {
        return new IndexSearcher(readers);
    }

    private static IndexSearcher parallelSearcher(List<LeafReader> readers) {
        Executor executor = IndexSearcher.newVirtualThreadExecutor();
        return new IndexSearcher(readers, executor);
    }

    @Test
    public void termQueryMatchesAcrossSeeds() throws Exception {
        for (long seed : SEEDS) {
            Random rnd = new Random(seed);
            int segments = 8 + rnd.nextInt(5);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = sequentialSearcher(readers);
            IndexSearcher par = parallelSearcher(readers);
            assertTrue(par.slices().size() >= 1);
            Query query = new TermQuery(new Term("text", "needle"));
            TopDocs expected = seq.search(query, 10);
            TopDocs actual = par.search(query, 10);
            assertSameTopDocs(expected, actual);
        }
    }

    @Test
    public void booleanQueryMatchesAcrossSeeds() throws Exception {
        for (long seed : SEEDS) {
            Random rnd = new Random(seed + 100);
            int segments = 8 + rnd.nextInt(5);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = sequentialSearcher(readers);
            IndexSearcher par = parallelSearcher(readers);
            BooleanQuery query = BooleanQuery.builder()
                .add(new TermQuery(new Term("text", "needle")), BooleanQuery.Occur.SHOULD)
                .add(new TermQuery(new Term("text", "alpha")), BooleanQuery.Occur.SHOULD)
                .add(new TermQuery(new Term("text", "hay")), BooleanQuery.Occur.MUST_NOT)
                .setMinimumShouldMatch(1)
                .build();
            TopDocs expected = seq.search(query, 12);
            TopDocs actual = par.search(query, 12);
            assertSameTopDocs(expected, actual);
        }
    }

    @Test
    public void phraseQueryMatchesAcrossSeeds() throws Exception {
        for (long seed : SEEDS) {
            Random rnd = new Random(seed + 200);
            int segments = 8 + rnd.nextInt(5);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = sequentialSearcher(readers);
            IndexSearcher par = parallelSearcher(readers);
            PhraseQuery query = new PhraseQuery("text", List.of(ALPHA, BETA), 0);
            TopDocs expected = seq.search(query, 10);
            TopDocs actual = par.search(query, 10);
            assertSameTopDocs(expected, actual);
        }
    }

    @Test
    public void sortedByLongFieldMatchesAcrossSeedsWithTies() throws Exception {
        for (long seed : SEEDS) {
            Random rnd = new Random(seed + 300);
            int segments = 8 + rnd.nextInt(5);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = sequentialSearcher(readers);
            IndexSearcher par = parallelSearcher(readers);
            Query query = new TermQuery(new Term("text", "hay"));
            Sort sort = new Sort(new SortField("num", SortField.Type.LONG));
            TopDocs expected = seq.search(query, 15, sort);
            TopDocs actual = par.search(query, 15, sort);
            assertSameTopDocs(expected, actual);

            Sort reverseSort = new Sort(new SortField("num", SortField.Type.LONG, true));
            TopDocs expectedRev = seq.search(query, 15, reverseSort);
            TopDocs actualRev = par.search(query, 15, reverseSort);
            assertSameTopDocs(expectedRev, actualRev);
        }
    }

    @Test
    public void sortedByStringFieldMatchesAcrossSeedsWithTies() throws Exception {
        for (long seed : SEEDS) {
            Random rnd = new Random(seed + 400);
            int segments = 8 + rnd.nextInt(5);
            List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
            IndexSearcher seq = sequentialSearcher(readers);
            IndexSearcher par = parallelSearcher(readers);
            Query query = new TermQuery(new Term("text", "hay"));
            Sort sort = new Sort(new SortField("cat", SortField.Type.STRING));
            TopDocs expected = seq.search(query, 15, sort);
            TopDocs actual = par.search(query, 15, sort);
            assertSameTopDocs(expected, actual);
        }
    }

    @Test
    public void allMatchesReturnedWhenTopNExceedsHitCount() throws Exception {
        Random rnd = new Random(999L);
        int segments = 9;
        List<LeafReader> readers = ConcurrentTestSupport.buildRandomCorpus(rnd, segments);
        IndexSearcher seq = sequentialSearcher(readers);
        IndexSearcher par = parallelSearcher(readers);
        Query query = new TermQuery(new Term("text", "needle"));
        TopDocs expected = seq.search(query, 100_000);
        TopDocs actual = par.search(query, 100_000);
        assertSameTopDocs(expected, actual);
    }
}
