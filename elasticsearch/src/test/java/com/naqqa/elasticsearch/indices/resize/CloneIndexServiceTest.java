package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.GetResult;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CloneIndexServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    public void cloneProducesExactDuplicateOfEachShard() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("clone-source");
        Path targetBase = ResizeTestSupport.newTempDir("clone-target");

        List<IndexShard> sourceShards = new ArrayList<>();
        for (int shardId = 0; shardId < 2; shardId++) {
            IndexShard shard = ResizeTestSupport.openSourceShard(sourceBase.resolve(String.valueOf(shardId)), mapperService);
            for (int docId = 0; docId < 5; docId++) {
                shard.index("s" + shardId + "-doc" + docId, Map.of("title", "hello-" + shardId + "-" + docId, "tag", "t" + docId));
            }
            shard.refresh();
            sourceShards.add(shard);
        }

        List<IndexShard> targetShards = CloneIndexService.clone(targetBase, sourceShards, mapperService, true);

        Assert.assertEquals(2, targetShards.size());
        for (int shardId = 0; shardId < 2; shardId++) {
            IndexShard source = sourceShards.get(shardId);
            IndexShard target = targetShards.get(shardId);
            Assert.assertEquals(source.docCount(), target.docCount());
            for (int docId = 0; docId < 5; docId++) {
                String id = "s" + shardId + "-doc" + docId;
                GetResult expected = source.get(id);
                GetResult actual = target.get(id);
                Assert.assertTrue(actual.exists(), "expected clone to contain doc " + id);
                Map<String, Object> expectedSource = (Map<String, Object>) JsonValue.parse(expected.source().toBytesArray()).toJava();
                Map<String, Object> actualSource = (Map<String, Object>) JsonValue.parse(actual.source().toBytesArray()).toJava();
                Assert.assertEquals(expectedSource, actualSource);
            }
        }

        IndexShard extraOnTarget = targetShards.get(0);
        extraOnTarget.index("only-on-target", Map.of("title", "new", "tag", "new"));
        extraOnTarget.refresh();
        Assert.assertFalse(sourceShards.get(0).get("only-on-target").exists(),
            "target shard directory must be independent from the source");

        for (IndexShard s : sourceShards) {
            s.close();
        }
        for (IndexShard t : targetShards) {
            t.close();
        }
    }

    @Test
    public void cloneRejectsIndexThatIsNotReadOnly() throws Exception {
        MapperService mapperService = ResizeTestSupport.newMapperService();
        Path sourceBase = ResizeTestSupport.newTempDir("clone-source-rw");
        Path targetBase = ResizeTestSupport.newTempDir("clone-target-rw");
        IndexShard shard = ResizeTestSupport.openSourceShard(sourceBase.resolve("0"), mapperService);
        shard.index("doc1", Map.of("title", "x", "tag", "y"));
        shard.refresh();
        List<IndexShard> sourceShards = List.of(shard);

        Assert.assertThrows(IndexNotReadOnlyException.class,
            () -> CloneIndexService.clone(targetBase, sourceShards, mapperService, false));

        shard.close();
    }
}
