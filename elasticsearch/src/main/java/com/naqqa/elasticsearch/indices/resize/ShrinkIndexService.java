package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ShrinkIndexService {

    private ShrinkIndexService() {
    }

    public static List<IndexShard> shrink(Path targetIndexPath, int sourceShardCount, int targetShardCount,
                                           List<IndexShard> sourceShards, MapperService mapperService,
                                           boolean sourceIsReadOnly) throws IOException {
        if (!sourceIsReadOnly) {
            throw new IndexNotReadOnlyException(mapperService.indexName());
        }
        if (sourceShards.size() != sourceShardCount) {
            throw new IllegalArgumentException(
                "expected [" + sourceShardCount + "] source shards but got [" + sourceShards.size() + "]");
        }
        RoutingShardResolver.validateShrink(sourceShardCount, targetShardCount);

        for (IndexShard shard : sourceShards) {
            shard.flush(true);
        }

        List<IndexShard> targetShards = new ArrayList<>(targetShardCount);
        for (int targetId = 0; targetId < targetShardCount; targetId++) {
            List<Integer> sourceOrdinals = RoutingShardResolver.sourceShardsForTargetShard(targetId, sourceShardCount, targetShardCount);

            Path targetShardPath = targetIndexPath.resolve(String.valueOf(targetId));
            Path targetIndexDir = targetShardPath.resolve("index");
            List<SegmentCommitInfo> newCommitInfos = new ArrayList<>();
            Set<String> allFiles = new LinkedHashSet<>();
            long maxSeqNo = -1L;
            long localCheckpoint = -1L;
            try (Directory destDir = new FSDirectory(targetIndexDir)) {
                for (int sourceOrdinal : sourceOrdinals) {
                    IndexShard source = sourceShards.get(sourceOrdinal);
                    Directory srcDir = source.engine().config().directory();
                    SegmentInfos lastCommit = SegmentInfos.readLatestCommit(srcDir);
                    String prefix = "s" + sourceOrdinal + "_";
                    for (SegmentCommitInfo sci : lastCommit.segments()) {
                        String newName = prefix + sci.segmentName();
                        SegmentCommitInfo newSci = SegmentCopyUtil.copySegment(srcDir, destDir, sci, newName, allFiles);
                        newCommitInfos.add(newSci);
                    }
                    EngineStats stats = source.stats();
                    maxSeqNo = Math.max(maxSeqNo, stats.maxSeqNo());
                    localCheckpoint = Math.max(localCheckpoint, stats.localCheckpoint());
                }
                SegmentCopyUtil.commitNewSegments(destDir, newCommitInfos, allFiles, localCheckpoint, maxSeqNo);
            }
            IndexShard targetShard = IndexShard.open(targetShardPath, mapperService);
            if (targetShard.docCount() > 0) {
                targetShard.forceMerge(1);
            }
            targetShards.add(targetShard);
        }
        return targetShards;
    }
}
