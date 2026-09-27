package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class SplitIndexServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    public void splitOneToFourRedistributesEveryDocByRoutingHash() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("split1to4-source");
        Path targetBase = ResizeTestSupport.newTempDir("split1to4-target");

        IndexShard source = ResizeTestSupport.openSourceShard(sourceBase.resolve("0"), mapperService);
        int totalDocs = 40;
        for (int docId = 0; docId < totalDocs; docId++) {
            source.index("doc-" + docId, Map.of("title", "hello-" + docId, "tag", "t" + docId));
        }
        source.refresh();

        List<IndexShard> targetShards = SplitIndexService.split(targetBase, 1, 4, List.of(source), mapperService, true);
        Assert.assertEquals(4, targetShards.size());

        int totalAfterSplit = 0;
        for (IndexShard t : targetShards) {
            totalAfterSplit += t.docCount();
        }
        Assert.assertEquals(totalDocs, totalAfterSplit);

        for (int docId = 0; docId < totalDocs; docId++) {
            String id = "doc-" + docId;
            int expectedShard = RoutingShardResolver.shardForRouting(id, 4);
            GetResult expected = source.get(id);
            Map<String, Object> expectedSource = (Map<String, Object>) JsonValue.parse(expected.source().toBytesArray()).toJava();
            for (int targetId = 0; targetId < 4; targetId++) {
                GetResult r = targetShards.get(targetId).get(id);
                if (targetId == expectedShard) {
                    Assert.assertTrue(r.exists(), "expected shard " + targetId + " to contain " + id);
                    Map<String, Object> actualSource = (Map<String, Object>) JsonValue.parse(r.source().toBytesArray()).toJava();
                    Assert.assertEquals(expectedSource, actualSource);
                } else {
                    Assert.assertFalse(r.exists(), "did not expect shard " + targetId + " to contain " + id);
                }
            }
        }

        source.close();
        for (IndexShard t : targetShards) {
            t.close();
        }
    }

    @Test
    public void splitHonorsExplicitRoutingOverId() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("split-routing-source");
        Path targetBase = ResizeTestSupport.newTempDir("split-routing-target");

        IndexShard source = ResizeTestSupport.openSourceShard(sourceBase.resolve("0"), mapperService);
        String routingKey = "customer-42";
        source.index("order-1", routingKey, Map.of("title", "order one", "tag", "a"));
        source.index("order-2", routingKey, Map.of("title", "order two", "tag", "b"));
        source.refresh();

        List<IndexShard> targetShards = SplitIndexService.split(targetBase, 1, 4, List.of(source), mapperService, true);

        int expectedShard = RoutingShardResolver.shardForRouting(routingKey, 4);
        Assert.assertTrue(targetShards.get(expectedShard).get("order-1").exists());
        Assert.assertTrue(targetShards.get(expectedShard).get("order-2").exists());
        int found = 0;
        for (int i = 0; i < 4; i++) {
            if (i != expectedShard) {
                Assert.assertFalse(targetShards.get(i).get("order-1").exists());
                Assert.assertFalse(targetShards.get(i).get("order-2").exists());
            } else {
                found++;
            }
        }
        Assert.assertEquals(1, found);

        source.close();
        for (IndexShard t : targetShards) {
            t.close();
        }
    }

    @Test
    public void splitRejectsNonReadOnlySource() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("split-rw-source");
        Path targetBase = ResizeTestSupport.newTempDir("split-rw-target");
        IndexShard source = ResizeTestSupport.openSourceShard(sourceBase.resolve("0"), mapperService);
        source.index("doc1", Map.of("title", "x", "tag", "y"));
        source.refresh();
        List<IndexShard> sourceShards = List.of(source);

        Assert.assertThrows(IndexNotReadOnlyException.class,
            () -> SplitIndexService.split(targetBase, 1, 4, sourceShards, mapperService, false));

        source.close();
    }

    @Test
    public void splitRejectsInvalidShardCountCombination() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("split-invalid-source");
        Path targetBase = ResizeTestSupport.newTempDir("split-invalid-target");
        List<IndexShard> sourceShards = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            IndexShard shard = ResizeTestSupport.openSourceShard(sourceBase.resolve(String.valueOf(i)), mapperService);
            shard.index("doc-" + i, Map.of("title", "x", "tag", "y"));
            shard.refresh();
            sourceShards.add(shard);
        }

        IllegalArgumentException ex = Assert.assertThrows(IllegalArgumentException.class,
            () -> SplitIndexService.split(targetBase, 3, 8, sourceShards, mapperService, true));
        Assert.assertTrue(ex.getMessage().contains("must be a factor of"));

        for (IndexShard shard : sourceShards) {
            shard.close();
        }
    }
}
