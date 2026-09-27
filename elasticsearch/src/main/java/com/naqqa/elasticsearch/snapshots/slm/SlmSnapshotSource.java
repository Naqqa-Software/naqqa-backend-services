package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.source.RepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;

import java.util.List;
import java.util.Map;

public interface SlmSnapshotSource {

    List<String> indices();

    Map<ShardId, ShardSnapshotSource> shardSources();

    RepositoryMetadataSource metadataSource();
}
