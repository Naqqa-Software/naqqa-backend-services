package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.search.advanced.common.SegmentReaderLeafAdapter;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReader;
import com.naqqa.elasticsearch.search.execution.QueryCache;
import com.naqqa.elasticsearch.search.execution.QueryCaches;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class FilterCacheEngineTest {

    @Test
    public void filterCacheProducesCorrectResultsAcrossSegmentsDeletesAndMerge() throws IOException {
        QueryCache previousCache = QueryCaches.shared();
        try {
            QueryCaches.setShared(new QueryCache(QueryCache.DEFAULT_MAX_BYTES, 1, 0));

            Path shardPath = EngineTestSupport.newTempShardPath();
            MapperService mapperService = EngineTestSupport.newMapperService();
            InternalEngine engine = EngineTestSupport.open(shardPath, mapperService);
            try {
                TreeSet<String> liveWithTagA = new TreeSet<>();
                int docNum = 0;
                for (int batch = 0; batch < 4; batch++) {
                    for (int i = 0; i < 10; i++) {
                        String id = "doc-" + docNum++;
                        String tag = i % 3 == 0 ? "a" : i % 3 == 1 ? "b" : "c";
                        engine.index(IndexOperation.of(id, Map.of("title", "text " + id, "tag", tag)));
                        if ("a".equals(tag)) {
                            liveWithTagA.add(id);
                        }
                    }
                    engine.refresh("batch-" + batch);
                }

                assertEquals(liveWithTagA.size(), countTagA(engine));
                assertEquals(liveWithTagA.size(), countTagA(engine));
                assertEquals(liveWithTagA.size(), countTagA(engine), "repeated cached lookups must agree with the uncached first lookup");

                List<String> toDelete = new ArrayList<>(liveWithTagA).subList(0, Math.min(3, liveWithTagA.size()));
                for (String id : new ArrayList<>(toDelete)) {
                    engine.delete(DeleteOperation.of(id));
                    liveWithTagA.remove(id);
                }
                engine.refresh("after-delete");

                assertEquals(liveWithTagA.size(), countTagA(engine), "deleted docs must be excluded even though the filter bitset is cached");
                assertEquals(liveWithTagA.size(), countTagA(engine));

                engine.forceMerge(1);

                assertEquals(liveWithTagA.size(), countTagA(engine), "results must stay correct after the old segments are merged away");
                assertEquals(liveWithTagA.size(), countTagA(engine));
            } finally {
                engine.close();
            }
        } finally {
            QueryCaches.setShared(previousCache);
        }
    }

    private static int countTagA(InternalEngine engine) throws IOException {
        try (EngineSearcher engineSearcher = engine.acquireSearcher()) {
            List<LeafReader> leaves = SegmentReaderLeafAdapter.wrap(engineSearcher.leaves());
            IndexSearcher searcher = new IndexSearcher(leaves);
            BooleanQuery query = BooleanQuery.builder().add(new TermQuery(new Term("tag", "a")), BooleanQuery.Occur.FILTER).build();
            return searcher.count(query);
        }
    }
}
