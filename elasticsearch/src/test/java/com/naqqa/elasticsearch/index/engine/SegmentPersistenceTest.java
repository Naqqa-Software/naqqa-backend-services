package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.analysis.registry.AnalysisRegistry;
import com.naqqa.elasticsearch.analysis.registry.IndexAnalyzers;
import com.naqqa.elasticsearch.codec.termvectors.TermVectorTerm;
import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.index.engine.segment.EngineNestedDocMapping;
import com.naqqa.elasticsearch.index.engine.segment.EngineVectorAccessorProvider;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.SegmentWriter;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Operation;
import com.naqqa.elasticsearch.index.translog.Translog;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.bridge.geo.GeoBoundingBoxQuery;
import com.naqqa.elasticsearch.search.bridge.join.JoinScoreMode;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.ScoreDoc;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.PointRangeQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.query.KnnVectorQuery;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SegmentPersistenceTest {

    private static MapperService mapperService(Map<String, Object> properties) {
        IndexAnalyzers analyzers = new AnalysisRegistry().build(Map.of());
        MapperService ms = new MapperService(analyzers, Settings.EMPTY, "test-index");
        ms.putMapping(Map.of("properties", properties));
        return ms;
    }

    private static IndexShard openShard(Path path, MapperService ms) throws IOException {
        return IndexShard.open(EngineTestSupport.newConfig(path, ms), ms);
    }

    private static Set<String> ids(IndexSearcher searcher, TopDocs top) throws IOException {
        Set<String> out = new TreeSet<>();
        for (ScoreDoc sd : top.scoreDocs()) {
            out.add(idOf(searcher, sd.doc));
        }
        return out;
    }

    private static String idOf(IndexSearcher searcher, int globalDoc) throws IOException {
        for (LeafReaderContext ctx : searcher.leafContexts()) {
            int local = globalDoc - ctx.docBase();
            if (local >= 0 && local < ctx.reader().maxDoc()) {
                SegmentReader sr = ((SegmentReaderLeafAdapter) ctx.reader()).segmentReader();
                return sr.storedDocument(local).id();
            }
        }
        throw new IllegalStateException("doc not found " + globalDoc);
    }

    private static long day(String iso) {
        return LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static Map<String, Object> pointMapping() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("price", Map.of("type", "integer"));
        props.put("amount", Map.of("type", "long"));
        props.put("when", Map.of("type", "date"));
        props.put("loc", Map.of("type", "geo_point"));
        return props;
    }

    private static Map<String, Object> pointDoc(int price, long amount, String when, double lat, double lon) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("price", price);
        doc.put("amount", amount);
        doc.put("when", when);
        doc.put("loc", Map.of("lat", lat, "lon", lon));
        return doc;
    }

    private static void assertPointQueries(IndexShard shard) throws IOException {
        try (EngineSearcher es = shard.acquireSearcher()) {
            IndexSearcher searcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(es.leaves()));
            assertEquals(Set.of("2", "3"), ids(searcher, searcher.search(PointRangeQuery.newIntRange("price", 15, 35), 10)));
            assertEquals(Set.of("1", "2", "3", "4"), ids(searcher, searcher.search(PointRangeQuery.newIntRange("price", 0, 100), 10)));
            assertEquals(Set.of("4"), ids(searcher, searcher.search(PointRangeQuery.newLongRange("amount", 5_000_000_000L, Long.MAX_VALUE), 10)));
            assertEquals(Set.of("1", "2"), ids(searcher, searcher.search(
                PointRangeQuery.newLongRange("when", day("2024-01-01"), day("2024-02-01")), 10)));
            assertEquals(Set.of("1", "3"), ids(searcher, searcher.search(new GeoBoundingBoxQuery("loc", 40.0, 41.0, -75.0, -73.0), 10)));
            assertEquals(4, searcher.count(new MatchAllDocsQuery()));
        }
    }

    @Test
    public void pointFieldsArePersistedMergedAndReloaded() throws IOException {
        Path path = EngineTestSupport.newTempShardPath();
        MapperService ms = mapperService(pointMapping());
        IndexShard shard = openShard(path, ms);
        try {
            shard.index("1", pointDoc(10, 100L, "2024-01-05", 40.7, -74.0));
            shard.index("2", pointDoc(20, 200L, "2024-01-20", 10.0, 10.0));
            shard.refresh();
            shard.index("3", pointDoc(30, 300L, "2024-03-01", 40.1, -73.5));
            shard.index("4", pointDoc(40, 9_000_000_000L, "2023-12-31", -30.0, 150.0));
            shard.index("5", pointDoc(25, 250L, "2024-01-10", 40.5, -74.5));
            shard.refresh();
            shard.delete("5");
            shard.refresh();
            try (EngineSearcher es = shard.acquireSearcher()) {
                assertTrue(es.leaves().size() >= 2);
                boolean any = false;
                for (SegmentReader sr : es.leaves()) {
                    if (sr.pointValues("price") != null) {
                        any = true;
                        assertEquals(1, sr.pointValues("price").numDims());
                        assertEquals(4, sr.pointValues("price").bytesPerDim());
                    }
                }
                assertTrue(any);
            }
            assertPointQueries(shard);
            shard.forceMerge(1);
            assertEquals(1, shard.segmentCount());
            assertPointQueries(shard);
            shard.flush(true);
        } finally {
            shard.close();
        }
        IndexShard reopened = openShard(path, ms);
        try {
            assertPointQueries(reopened);
        } finally {
            reopened.close();
        }
    }

    private static float[] randomVector(Random random, int dims) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) {
            v[i] = random.nextFloat() * 2f - 1f;
        }
        return v;
    }

    private static List<Float> boxed(float[] v) {
        List<Float> out = new ArrayList<>(v.length);
        for (float f : v) {
            out.add(f);
        }
        return out;
    }

    private static double recall(IndexShard shard, Map<String, float[]> live, float[] query, int k) throws IOException {
        List<Map.Entry<String, float[]>> entries = new ArrayList<>(live.entrySet());
        entries.sort((a, b) -> Float.compare(VectorSimilarity.L2_NORM.score(query, b.getValue()),
            VectorSimilarity.L2_NORM.score(query, a.getValue())));
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < k; i++) {
            expected.add(entries.get(i).getKey());
        }
        try (EngineSearcher es = shard.acquireSearcher()) {
            IndexSearcher searcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(es.leaves()));
            KnnVectorQuery q = new KnnVectorQuery("vec", query, k, 64, null, new EngineVectorAccessorProvider());
            TopDocs top = searcher.search(q, k);
            Set<String> got = ids(searcher, top);
            for (String id : got) {
                assertTrue(live.containsKey(id), "deleted doc returned by knn: " + id);
            }
            int hit = 0;
            for (String id : got) {
                if (expected.contains(id)) {
                    hit++;
                }
            }
            return (double) hit / k;
        }
    }

    private static double averageRecall(IndexShard shard, Map<String, float[]> live, long seed) throws IOException {
        Random random = new Random(seed);
        double total = 0;
        int queries = 20;
        for (int i = 0; i < queries; i++) {
            total += recall(shard, live, randomVector(random, 8), 10);
        }
        return total / queries;
    }

    @Test
    public void denseVectorsArePersistedAndSearchableWithKnn() throws IOException {
        Path path = EngineTestSupport.newTempShardPath();
        MapperService ms = mapperService(Map.of("vec", Map.of("type", "dense_vector", "dims", 8, "similarity", "l2_norm")));
        IndexShard shard = openShard(path, ms);
        Map<String, float[]> live = new LinkedHashMap<>();
        Random random = new Random(7);
        try {
            for (int i = 0; i < 300; i++) {
                float[] v = randomVector(random, 8);
                shard.index("d" + i, Map.of("vec", boxed(v)));
                live.put("d" + i, v);
                if (i % 100 == 99) {
                    shard.refresh();
                }
            }
            for (int i = 0; i < 300; i += 7) {
                shard.delete("d" + i);
                live.remove("d" + i);
            }
            shard.refresh();
            try (EngineSearcher es = shard.acquireSearcher()) {
                for (SegmentReader sr : es.leaves()) {
                    assertNotNull(sr.vectorReader("vec"));
                    assertTrue(sr.vectorFields().contains("vec"));
                }
            }
            double before = averageRecall(shard, live, 99);
            assertTrue(before >= 0.9, "recall before merge " + before);
            shard.forceMerge(1);
            double afterMerge = averageRecall(shard, live, 99);
            assertTrue(afterMerge >= 0.9, "recall after merge " + afterMerge);
            shard.flush(true);
        } finally {
            shard.close();
        }
        IndexShard reopened = openShard(path, ms);
        try {
            double afterRestart = averageRecall(reopened, live, 99);
            assertTrue(afterRestart >= 0.9, "recall after restart " + afterRestart);
        } finally {
            reopened.close();
        }
    }

    private static Map<String, Object> nestedMapping() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "keyword"));
        props.put("comments", Map.of("type", "nested", "properties", Map.of(
            "author", Map.of("type", "keyword"),
            "stars", Map.of("type", "integer"))));
        return props;
    }

    private static Set<String> nestedIds(IndexShard shard, String author) throws IOException {
        try (EngineSearcher es = shard.acquireSearcher()) {
            IndexSearcher searcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(es.leaves()));
            EngineNestedDocMapping mapping = new EngineNestedDocMapping(es.leaves(), "comments");
            Query q = mapping.nestedQuery(new TermQuery(new Term("comments.author", author)), JoinScoreMode.AVG);
            return ids(searcher, searcher.search(q, 10));
        }
    }

    private static void assertTopLevelHidesChildren(IndexShard shard, int expectedRoots) throws IOException {
        try (EngineSearcher es = shard.acquireSearcher()) {
            IndexSearcher searcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(es.leaves()));
            assertEquals(expectedRoots, searcher.count(new MatchAllDocsQuery()));
            assertEquals(0, searcher.count(new TermQuery(new Term("comments.author", "bob"))));
            assertEquals(expectedRoots, es.numDocs());
            Set<String> matchAllIds = ids(searcher, searcher.search(new MatchAllDocsQuery(), 100));
            assertEquals(expectedRoots, matchAllIds.size());
        }
    }

    @Test
    public void nestedDocumentsAreIndexedAsHiddenBlocks() throws IOException {
        Path path = EngineTestSupport.newTempShardPath();
        MapperService ms = mapperService(nestedMapping());
        IndexShard shard = openShard(path, ms);
        try {
            shard.index("1", Map.of("title", "a", "comments", List.of(
                Map.of("author", "bob", "stars", 5), Map.of("author", "alice", "stars", 2))));
            shard.index("2", Map.of("title", "b", "comments", List.of(Map.of("author", "bob", "stars", 1))));
            shard.refresh();
            shard.index("3", Map.of("title", "c"));
            shard.index("4", Map.of("title", "d", "comments", List.of(Map.of("author", "carl", "stars", 3))));
            shard.refresh();

            try (EngineSearcher es = shard.acquireSearcher()) {
                int nested = 0;
                for (SegmentReader sr : es.leaves()) {
                    for (int d = 0; d < sr.maxDoc(); d++) {
                        if (sr.isNestedDoc(d)) {
                            nested++;
                            assertEquals("comments", sr.nestedPath(d));
                            int root = sr.rootDocOf(d);
                            assertTrue(root > d);
                            assertTrue(!sr.isNestedDoc(root));
                            assertTrue(sr.isLiveIncludingNested(d));
                            assertTrue(!sr.isLive(d));
                        }
                    }
                }
                assertEquals(4, nested);
            }
            assertTopLevelHidesChildren(shard, 4);
            assertEquals(Set.of("1", "2"), nestedIds(shard, "bob"));
            assertEquals(Set.of("1"), nestedIds(shard, "alice"));
            assertEquals(Set.of("4"), nestedIds(shard, "carl"));

            shard.index("1", Map.of("title", "a", "comments", List.of(Map.of("author", "bob", "stars", 4))));
            shard.delete("4");
            shard.refresh();
            assertEquals(Set.of(), nestedIds(shard, "alice"));
            assertEquals(Set.of(), nestedIds(shard, "carl"));
            assertEquals(Set.of("1", "2"), nestedIds(shard, "bob"));
            assertTopLevelHidesChildren(shard, 3);

            shard.forceMerge(1);
            try (EngineSearcher es = shard.acquireSearcher()) {
                SegmentReader merged = es.leaves().get(0);
                assertEquals(1, es.leaves().size());
                assertEquals(5, merged.maxDoc());
                assertEquals(0, merged.deletedDocCount());
                assertEquals(3, merged.numDocs());
                assertEquals(5, merged.numDocsIncludingNested());
            }
            assertEquals(Set.of("1", "2"), nestedIds(shard, "bob"));
            assertTopLevelHidesChildren(shard, 3);
            shard.flush(true);
        } finally {
            shard.close();
        }
        IndexShard reopened = openShard(path, ms);
        try {
            assertEquals(Set.of("1", "2"), nestedIds(reopened, "bob"));
            assertEquals(Set.of(), nestedIds(reopened, "alice"));
            assertTopLevelHidesChildren(reopened, 3);
            assertEquals(3, reopened.docCount());
            assertTrue(reopened.get("1").exists());
        } finally {
            reopened.close();
        }
    }

    @Test
    public void multiLevelNestedDocumentsJoinToRootOrNestedParent() throws IOException {
        Path path = EngineTestSupport.newTempShardPath();
        MapperService ms = mapperService(Map.of("sections", Map.of("type", "nested", "properties", Map.of(
            "name", Map.of("type", "keyword"),
            "paras", Map.of("type", "nested", "properties", Map.of("word", Map.of("type", "keyword")))))));
        IndexShard shard = openShard(path, ms);
        try {
            shard.index("1", Map.of("sections", List.of(
                Map.of("name", "s1", "paras", List.of(Map.of("word", "x"), Map.of("word", "y"))),
                Map.of("name", "s2", "paras", List.of(Map.of("word", "z"))))));
            shard.index("2", Map.of("sections", List.of(Map.of("name", "s3", "paras", List.of(Map.of("word", "x"))))));
            shard.refresh();
            try (EngineSearcher es = shard.acquireSearcher()) {
                IndexSearcher searcher = new IndexSearcher(SegmentReaderLeafAdapter.wrap(es.leaves()));
                EngineNestedDocMapping base = new EngineNestedDocMapping(es.leaves());
                Query toRoot = base.forPath("sections.paras").nestedQuery(new TermQuery(new Term("sections.paras.word", "x")), JoinScoreMode.MAX);
                assertEquals(Set.of("1", "2"), ids(searcher, searcher.search(toRoot, 10)));
                Query zToRoot = base.forPath("sections.paras").nestedQuery(new TermQuery(new Term("sections.paras.word", "z")), JoinScoreMode.MAX);
                assertEquals(Set.of("1"), ids(searcher, searcher.search(zToRoot, 10)));
                SegmentReader sr = es.leaves().get(0);
                EngineNestedDocMapping toSection = EngineNestedDocMapping.forSegment(sr, "sections.paras").forPath("sections.paras", "sections");
                for (int d = 0; d < sr.maxDoc(); d++) {
                    if (toSection.isChild(d)) {
                        assertEquals("sections", sr.nestedPath(toSection.parentOf(d)));
                    }
                }
                assertEquals(2, searcher.count(new MatchAllDocsQuery()));
            }
        } finally {
            shard.close();
        }
    }

    private static TermVectorTerm find(TermVectorTerm[] terms, String term) {
        for (TermVectorTerm t : terms) {
            if (new String(t.term(), StandardCharsets.UTF_8).equals(term)) {
                return t;
            }
        }
        return null;
    }

    private static void assertTermVectors(IndexShard shard) throws IOException {
        try (EngineSearcher es = shard.acquireSearcher()) {
            boolean found = false;
            for (SegmentReader sr : es.leaves()) {
                Integer doc = sr.findLiveDocForId(SegmentWriter.ID_FIELD, "1");
                if (doc == null) {
                    continue;
                }
                found = true;
                TermVectorTerm[] terms = sr.termVectors("body", doc);
                assertNotNull(terms);
                assertEquals(4, terms.length);
                assertEquals("brown", new String(terms[0].term(), StandardCharsets.UTF_8));
                TermVectorTerm the = find(terms, "the");
                assertNotNull(the);
                assertEquals(2, the.freq());
                assertEquals(0, the.positions()[0]);
                assertEquals(4, the.positions()[1]);
                assertEquals(0, the.startOffsets()[0]);
                assertEquals(3, the.endOffsets()[0]);
                TermVectorTerm fox = find(terms, "fox");
                assertEquals(16, fox.startOffsets()[0]);
                assertEquals(19, fox.endOffsets()[0]);
                assertNull(sr.termVectors("plain", doc));
                assertTrue(sr.termVectors(doc).containsKey("body"));
                assertTrue(sr.termVectorFields().contains("body"));
            }
            assertTrue(found);
        }
    }

    @Test
    public void termVectorsArePersistedForEnabledFields() throws IOException {
        Path path = EngineTestSupport.newTempShardPath();
        MapperService ms = mapperService(Map.of(
            "body", Map.of("type", "text", "term_vector", "with_positions_offsets"),
            "plain", Map.of("type", "text")));
        IndexShard shard = openShard(path, ms);
        try {
            shard.index("1", Map.of("body", "the quick brown fox the", "plain", "nothing here"));
            shard.refresh();
            shard.index("2", Map.of("body", "lazy dog", "plain", "x"));
            shard.refresh();
            assertTermVectors(shard);
            shard.forceMerge(1);
            assertTermVectors(shard);
            shard.flush(true);
        } finally {
            shard.close();
        }
        IndexShard reopened = openShard(path, ms);
        try {
            assertTermVectors(reopened);
        } finally {
            reopened.close();
        }
    }

    @Test
    public void translogSnapshotIsExposedThroughEngineAndShard() throws IOException {
        Path path = EngineTestSupport.newTempShardPath();
        MapperService ms = mapperService(Map.of("title", Map.of("type", "keyword")));
        IndexShard shard = openShard(path, ms);
        try {
            shard.index("1", Map.of("title", "a"));
            shard.index("2", Map.of("title", "b"));
            shard.delete("1");
            List<Long> seqNos = new ArrayList<>();
            try (Translog.Snapshot snapshot = shard.newTranslogSnapshot(1)) {
                Operation op;
                while ((op = snapshot.next()) != null) {
                    seqNos.add(op.seqNo());
                }
            }
            assertEquals(List.of(1L, 2L), seqNos);
            try (Translog.Snapshot snapshot = shard.engine().newTranslogSnapshot(-5)) {
                assertEquals(3, snapshot.totalOperations());
            }
        } finally {
            shard.close();
        }
    }
}
