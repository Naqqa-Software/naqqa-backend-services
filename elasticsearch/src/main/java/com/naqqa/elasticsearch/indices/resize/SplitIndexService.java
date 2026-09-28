package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.store.Directory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SplitIndexService {

    private SplitIndexService() {
    }

    @SuppressWarnings("unchecked")
    public static List<IndexShard> split(Path targetIndexPath, int sourceShardCount, int targetShardCount,
                                          List<IndexShard> sourceShards, MapperService mapperService,
                                          boolean sourceIsReadOnly) throws IOException {
        if (!sourceIsReadOnly) {
            throw new IndexNotReadOnlyException(mapperService.indexName());
        }
        if (sourceShards.size() != sourceShardCount) {
            throw new IllegalArgumentException(
                "expected [" + sourceShardCount + "] source shards but got [" + sourceShards.size() + "]");
        }
        RoutingShardResolver.validateSplit(sourceShardCount, targetShardCount);

        List<IndexShard> targetShards = new ArrayList<>(targetShardCount);
        for (int targetId = 0; targetId < targetShardCount; targetId++) {
            Path targetShardPath = targetIndexPath.resolve(String.valueOf(targetId));
            targetShards.add(IndexShard.open(targetShardPath, mapperService));
        }

        for (IndexShard source : sourceShards) {
            source.flush(true);
            Directory srcDir = source.engine().config().directory();
            try (Releasable commitRef = source.acquireLastCommitRef()) {
                SegmentInfos lastCommit = SegmentInfos.readLatestCommit(srcDir);
                for (SegmentCommitInfo sci : lastCommit.segments()) {
                    SegmentReader reader = SegmentReader.open(srcDir, sci);
                    try {
                        int maxDoc = reader.maxDoc();
                        for (int docId = 0; docId < maxDoc; docId++) {
                            if (!reader.isLive(docId)) {
                                continue;
                            }
                            StoredDocCodec.Decoded decoded = reader.storedDocument(docId);
                            byte[] routingBytes = decoded.extraStoredFields().get("_routing");
                            String explicitRouting = routingBytes == null ? null : new String(routingBytes, StandardCharsets.UTF_8);
                            String effectiveRoutingKey = explicitRouting != null ? explicitRouting : decoded.id();
                            int targetShardId = RoutingShardResolver.shardForRouting(effectiveRoutingKey, targetShardCount);
                            Map<String, Object> sourceMap = (Map<String, Object>) JsonValue.parse(decoded.source()).toJava();
                            IndexShard targetShard = targetShards.get(targetShardId);
                            if (explicitRouting != null) {
                                targetShard.index(decoded.id(), explicitRouting, sourceMap);
                            } else {
                                targetShard.index(decoded.id(), sourceMap);
                            }
                        }
                    } finally {
                        reader.decRef();
                    }
                }
            }
        }

        for (IndexShard targetShard : targetShards) {
            targetShard.flush(true);
        }
        return targetShards;
    }
}
