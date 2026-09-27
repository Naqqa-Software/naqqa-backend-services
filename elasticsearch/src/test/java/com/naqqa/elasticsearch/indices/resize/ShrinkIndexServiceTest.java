package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ShrinkIndexServiceTest {

    private static List<IndexShard> buildSourceShards(Path base, MapperService mapperService, int shardCount, int docsPerShard) throws Exception {
        List<IndexShard> shards = new ArrayList<>();
        for (int shardId = 0; shardId < shardCount; shardId++) {
            IndexShard shard = ResizeTestSupport.openSourceShard(base.resolve(String.valueOf(shardId)), mapperService);
            for (int docId = 0; docId < docsPerShard; docId++) {
                shard.index("sh" + shardId + "-doc" + docId, Map.of("title", "hello-" + shardId + "-" + docId, "tag", "t" + docId));
            }
            shard.refresh();
            shards.add(shard);
        }
        return shards;
    }

    @Test
    public void shrinkFourToOneUnionsAllDocs() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("shrink4to1-source");
        Path targetBase = ResizeTestSupport.newTempDir("shrink4to1-target");

        List<IndexShard> sourceShards = buildSourceShards(sourceBase, mapperService, 4, 3);

        List<IndexShard> targetShards = ShrinkIndexService.shrink(targetBase, 4, 1, sourceShards, mapperService, true);
        Assert.assertEquals(1, targetShards.size());
        IndexShard merged = targetShards.get(0);

        int expectedTotal = 4 * 3;
        Assert.assertEquals(expectedTotal, merged.docCount());

        for (int shardId = 0; shardId < 4; shardId++) {
            for (int docId = 0; docId < 3; docId++) {
                String id = "sh" + shardId + "-doc" + docId;
                GetResult r = merged.get(id);
                Assert.assertTrue(r.exists(), "expected merged shard to contain " + id);
            }
        }

        for (IndexShard s : sourceShards) {
            s.close();
        }
        merged.close();
    }

    @Test
    public void shrinkFourToTwoGroupsShardsByRoutingResidue() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("shrink4to2-source");
        Path targetBase = ResizeTestSupport.newTempDir("shrink4to2-target");

        List<IndexShard> sourceShards = buildSourceShards(sourceBase, mapperService, 4, 3);

        List<IndexShard> targetShards = ShrinkIndexService.shrink(targetBase, 4, 2, sourceShards, mapperService, true);
        Assert.assertEquals(2, targetShards.size());

        for (int sourceShardId = 0; sourceShardId < 4; sourceShardId++) {
            int expectedTarget = RoutingShardResolver.targetShardForSourceShard(sourceShardId, 4, 2);
            for (int docId = 0; docId < 3; docId++) {
                String id = "sh" + sourceShardId + "-doc" + docId;
                for (int targetId = 0; targetId < 2; targetId++) {
                    GetResult r = targetShards.get(targetId).get(id);
                    if (targetId == expectedTarget) {
                        Assert.assertTrue(r.exists(), "expected target " + targetId + " to contain " + id);
                    } else {
                        Assert.assertFalse(r.exists(), "did not expect target " + targetId + " to contain " + id);
                    }
                }
            }
        }

        for (IndexShard s : sourceShards) {
            s.close();
        }
        for (IndexShard t : targetShards) {
            t.close();
        }
    }

    @Test
    public void shrinkRejectsNonReadOnlySource() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("shrink-rw-source");
        Path targetBase = ResizeTestSupport.newTempDir("shrink-rw-target");
        List<IndexShard> sourceShards = buildSourceShards(sourceBase, mapperService, 2, 1);

        Assert.assertThrows(IndexNotReadOnlyException.class,
            () -> ShrinkIndexService.shrink(targetBase, 2, 1, sourceShards, mapperService, false));

        for (IndexShard s : sourceShards) {
            s.close();
        }
    }

    @Test
    public void shrinkRejectsInvalidShardCountCombination() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("shrink-invalid-source");
        Path targetBase = ResizeTestSupport.newTempDir("shrink-invalid-target");
        List<IndexShard> sourceShards = buildSourceShards(sourceBase, mapperService, 5, 1);

        Assert.assertThrows(IllegalArgumentException.class,
            () -> ShrinkIndexService.shrink(targetBase, 5, 3, sourceShards, mapperService, true));

        for (IndexShard s : sourceShards) {
            s.close();
        }
    }
}
