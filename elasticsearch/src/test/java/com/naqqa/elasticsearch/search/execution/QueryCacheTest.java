package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.index.engine.BufferedDoc;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.SegmentWriter;
import com.naqqa.elasticsearch.index.mapper.IndexableField;
import com.naqqa.elasticsearch.index.mapper.IndexedTerm;
import com.naqqa.elasticsearch.index.mapper.ParsedDocument;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.store.ByteBuffersDirectory;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class QueryCacheTest {

    private static BooleanQuery filterQuery(int maxDoc, IntPredicate matches, AtomicInteger scorerBuilds, String label) {
        return BooleanQuery.builder().add(new CountingPredicateQuery(maxDoc, matches, scorerBuilds, label), BooleanQuery.Occur.FILTER)
            .build();
    }

    @Test
    public void filterBecomesCachedAfterUsageThresholdAndHitsIncreaseOnRepeat() throws IOException {
        QueryCache previous = QueryCaches.shared();
        try {
            QueryCache cache = new QueryCache(QueryCache.DEFAULT_MAX_BYTES, 2, 0);
            QueryCaches.setShared(cache);
            int maxDoc = 50;
            IndexSearcher searcher = new IndexSearcher(List.of(SimpleLeafReader.builder(maxDoc).build()));
            AtomicInteger builds = new AtomicInteger();
            IntPredicate even = d -> d % 2 == 0;
            int expected = 0;
            for (int d = 0; d < maxDoc; d++) {
                if (even.test(d)) {
                    expected++;
                }
            }

            assertEquals(expected, searcher.count(filterQuery(maxDoc, even, builds, "even")));
            assertEquals(1, builds.get(), "first use should evaluate the inner scorer directly");

            assertEquals(expected, searcher.count(filterQuery(maxDoc, even, builds, "even")));
            assertEquals(2, builds.get(), "second use crosses the usage threshold and builds the cached bitset");

            long hitsBefore = cache.stats().hitCount();
            assertEquals(expected, searcher.count(filterQuery(maxDoc, even, builds, "even")));
            assertEquals(2, builds.get(), "third use should be served from the cache without rebuilding the scorer");
            assertTrue(cache.stats().hitCount() > hitsBefore, "hit counter should increase on a cached repeat");

            assertEquals(expected, searcher.count(filterQuery(maxDoc, even, builds, "even")));
            assertEquals(2, builds.get());
        } finally {
            QueryCaches.setShared(previous);
        }
    }

    @Test
    public void evictsLeastRecentlyUsedEntriesUnderTinyByteLimit() throws IOException {
        QueryCache previous = QueryCaches.shared();
        try {
            QueryCache cache = new QueryCache(600, 1, 0);
            QueryCaches.setShared(cache);
            int maxDoc = 2000;
            IndexSearcher searcher = new IndexSearcher(List.of(SimpleLeafReader.builder(maxDoc).build()));
            AtomicInteger builds = new AtomicInteger();
            for (int i = 0; i < 10; i++) {
                int mod = i;
                searcher.count(filterQuery(maxDoc, d -> d % 7 == mod, builds, "mod" + mod));
            }
            QueryCache.Stats stats = cache.stats();
            assertTrue(stats.evictions() > 0, "expected evictions under a tiny byte budget");
            assertTrue(stats.memorySizeInBytes() <= 600, "cache should stay within its byte budget");
            assertTrue(stats.cacheSize() < 10, "not all 10 distinct filters should still be cached");
        } finally {
            QueryCaches.setShared(previous);
        }
    }

    @Test
    public void cachedAndUncachedSearchesProduceIdenticalResultsAcrossSegmentsAndRepeats() throws IOException {
        QueryCache previous = QueryCaches.shared();
        try {
            int[] sizes = {37, 128, 5, 64};
            List<LeafReader> leaves = new ArrayList<>();
            for (int size : sizes) {
                leaves.add(SimpleLeafReader.builder(size).build());
            }
            IndexSearcher searcher = new IndexSearcher(leaves);

            SplittableRandom random = new SplittableRandom(123);
            for (int trial = 0; trial < 5; trial++) {
                int mod = 2 + random.nextInt(5);
                int rem = random.nextInt(mod);
                IntPredicate predicate = d -> d % mod == rem;

                Set<Integer> expected = new TreeSet<>();
                int base = 0;
                for (int size : sizes) {
                    for (int d = 0; d < size; d++) {
                        if (predicate.test(d)) {
                            expected.add(base + d);
                        }
                    }
                    base += size;
                }

                AtomicInteger builds = new AtomicInteger();
                String label = "trial-" + trial;

                QueryCaches.setShared(new QueryCache(QueryCache.DEFAULT_MAX_BYTES, Integer.MAX_VALUE, 0));
                assertEquals(expected, docIds(searcher, filterQuery(0, predicate, builds, label)));

                QueryCaches.setShared(new QueryCache(QueryCache.DEFAULT_MAX_BYTES, 1, 0));
                for (int repeat = 0; repeat < 3; repeat++) {
                    assertEquals(expected, docIds(searcher, filterQuery(0, predicate, builds, label)));
                }
            }
        } finally {
            QueryCaches.setShared(previous);
        }
    }

    @Test
    public void closingASegmentDropsItsCacheEntries() throws IOException {
        QueryCache previous = QueryCaches.shared();
        try {
            QueryCache cache = new QueryCache(QueryCache.DEFAULT_MAX_BYTES, 1, 0);
            QueryCaches.setShared(cache);

            Directory dir = new ByteBuffersDirectory();
            String[] tags = {"a", "b", "a", "c", "a"};
            SegmentWriter.write(dir, "seg1", buildDocs(tags));
            SegmentReader segmentReader = SegmentReader.open(dir, new SegmentCommitInfo("seg1", 0, 0));
            SegmentReaderLeafAdapter adapter = new SegmentReaderLeafAdapter(segmentReader);
            IndexSearcher searcher = new IndexSearcher(List.of(adapter));

            Query rawFilter = new TermQuery(new Term("tag", "a"));
            BooleanQuery query = BooleanQuery.builder().add(rawFilter, BooleanQuery.Occur.FILTER).build();
            assertEquals(Set.of(0, 2, 4), docIds(searcher, query));

            assertTrue(cache.get(segmentReader, rawFilter) != null, "filter should be cached after being used once with minUses=1");
            assertEquals(1, cache.stats().cacheSize());

            segmentReader.decRef();

            assertNull(cache.get(segmentReader, rawFilter));
            assertEquals(0, cache.stats().cacheSize());
        } finally {
            QueryCaches.setShared(previous);
        }
    }

    private static List<BufferedDoc> buildDocs(String[] tags) {
        List<BufferedDoc> docs = new ArrayList<>();
        for (int i = 0; i < tags.length; i++) {
            String id = "doc" + i;
            IndexableField field = IndexableField.indexedText("tag", List.of(new IndexedTerm(tags[i], 0, 0, tags[i].length())), false);
            ParsedDocument parsed = new ParsedDocument(id, null, List.of(field), List.of(), new JsonObject(), List.of());
            docs.add(BufferedDoc.indexed(id, i, 0, 1, parsed));
        }
        return docs;
    }

    private static Set<Integer> docIds(IndexSearcher searcher, Query query) throws IOException {
        int maxDoc = 0;
        for (LeafReaderContext ctx : searcher.leafContexts()) {
            maxDoc += ctx.reader().maxDoc();
        }
        Set<Integer> ids = new TreeSet<>();
        for (var sd : searcher.search(query, Math.max(maxDoc, 1)).scoreDocs()) {
            ids.add(sd.doc);
        }
        return ids;
    }

    private static final class CountingPredicateQuery extends Query {
        private final int maxDoc;
        private final IntPredicate matches;
        private final AtomicInteger scorerBuilds;
        private final String label;

        CountingPredicateQuery(int maxDoc, IntPredicate matches, AtomicInteger scorerBuilds, String label) {
            this.maxDoc = maxDoc;
            this.matches = matches;
            this.scorerBuilds = scorerBuilds;
            this.label = label;
        }

        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
            return new Weight(this) {
                @Override
                public Scorer scorer(LeafReaderContext context) {
                    scorerBuilds.incrementAndGet();
                    int localMaxDoc = context.reader().maxDoc();
                    return new PredicateScorer(this, localMaxDoc, matches);
                }

                @Override
                public Explanation explain(LeafReaderContext context, int doc) {
                    return matches.test(doc) ? Explanation.match(1f, "matches predicate") : Explanation.noMatch("no match");
                }
            };
        }

        @Override
        public String toString() {
            return "Counting(" + label + ")";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CountingPredicateQuery q && label.equals(q.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(CountingPredicateQuery.class, label);
        }
    }

    private static final class PredicateScorer extends Scorer {
        private final int maxDoc;
        private final IntPredicate matches;
        private int doc = -1;

        PredicateScorer(Weight weight, int maxDoc, IntPredicate matches) {
            super(weight);
            this.maxDoc = maxDoc;
            this.matches = matches;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            doc++;
            while (doc < maxDoc && !matches.test(doc)) {
                doc++;
            }
            return doc >= maxDoc ? (doc = DocIdSetIterator.NO_MORE_DOCS) : doc;
        }

        @Override
        public int advance(int target) {
            doc = target - 1;
            return nextDoc();
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return 0f;
        }
    }
}
