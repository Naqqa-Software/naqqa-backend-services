package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.source.RepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CreateSnapshotRequest(
        String name,
        List<String> indices,
        Map<ShardId, ShardSnapshotSource> shardSources,
        RepositoryMetadataSource metadataSource,
        String policyId,
        CancellationToken cancellationToken,
        Instant now) {

    public CreateSnapshotRequest {
        indices = List.copyOf(indices);
        shardSources = Map.copyOf(shardSources);
        cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
    }

    public static CreateSnapshotRequest of(String name, List<String> indices,
            Map<ShardId, ShardSnapshotSource> shardSources, RepositoryMetadataSource metadataSource) {
        return new CreateSnapshotRequest(name, indices, shardSources, metadataSource, null, CancellationToken.NONE, null);
    }
}
