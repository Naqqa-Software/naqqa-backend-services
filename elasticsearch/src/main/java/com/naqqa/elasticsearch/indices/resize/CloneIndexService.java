package com.naqqa.elasticsearch.indices.resize;

import com.naqqa.elasticsearch.codec.segment.SegmentCommitInfo;
import com.naqqa.elasticsearch.codec.segment.SegmentInfos;
import com.naqqa.elasticsearch.index.engine.EngineStats;
import com.naqqa.elasticsearch.index.mapper.MapperService;
import com.naqqa.elasticsearch.index.shard.IndexShard;
import com.naqqa.elasticsearch.index.translog.Releasable;
import com.naqqa.elasticsearch.store.Directory;
import com.naqqa.elasticsearch.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CloneIndexService {

    private CloneIndexService() {
    }

    public static List<IndexShard> clone(Path targetIndexPath, List<IndexShard> sourceShards,
                                          MapperService mapperService, boolean sourceIsReadOnly) throws IOException {
        if (!sourceIsReadOnly) {
            throw new IndexNotReadOnlyException(mapperService.indexName());
        }
        List<IndexShard> targetShards = new ArrayList<>(sourceShards.size());
        for (int shardId = 0; shardId < sourceShards.size(); shardId++) {
            IndexShard source = sourceShards.get(shardId);
            source.flush(true);
            Directory srcDir = source.engine().config().directory();

            Path targetShardPath = targetIndexPath.resolve(String.valueOf(shardId));
            Path targetIndexDir = targetShardPath.resolve("index");
            List<SegmentCommitInfo> newCommitInfos = new ArrayList<>();
            Set<String> allFiles = new LinkedHashSet<>();
            try (Directory destDir = new FSDirectory(targetIndexDir);
                 Releasable commitRef = source.acquireLastCommitRef()) {
                SegmentInfos lastCommit = SegmentInfos.readLatestCommit(srcDir);
                for (SegmentCommitInfo sci : lastCommit.segments()) {
                    SegmentCommitInfo newSci = SegmentCopyUtil.copySegment(srcDir, destDir, sci, sci.segmentName(), allFiles);
                    newCommitInfos.add(newSci);
                }
                EngineStats stats = source.stats();
                SegmentCopyUtil.commitNewSegments(destDir, newCommitInfos, allFiles, stats.localCheckpoint(), stats.maxSeqNo());
            }
            targetShards.add(IndexShard.open(targetShardPath, mapperService));
        }
        return targetShards;
    }
}
