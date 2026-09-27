package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
import com.naqqa.elasticsearch.snapshots.restore.RestoreResult;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface Repository {

    String name();

    SnapshotInfo createSnapshot(CreateSnapshotRequest request) throws IOException;

    Optional<SnapshotInfo> getSnapshot(String name);

    List<SnapshotInfo> listSnapshots();

    void deleteSnapshot(String name) throws IOException;

    RestoreResult restoreSnapshot(String name, RestoreRequest request, Map<ShardId, ShardRestoreTarget> shardTargets) throws IOException;

    SnapshotInfo cloneSnapshot(String sourceName, String targetName) throws IOException;

    void verify() throws IOException;
}
