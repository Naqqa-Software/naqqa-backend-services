package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.model.SnapshotState;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
import com.naqqa.elasticsearch.snapshots.restore.RestoreResult;
import com.naqqa.elasticsearch.snapshots.source.InMemoryRepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.InMemoryShardRestoreTarget;
import com.naqqa.elasticsearch.snapshots.source.InMemoryShardSnapshotSource;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class FsRepositorySnapshotRestoreTest {

    private FsRepository newRepository(Path dir) {
        return new FsRepository("repo1", new FsBlobStore(dir));
    }

    @Test
    public void fullRoundTripCreateRestoreVerifiesContent() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-roundtrip");
        FsRepository repository = newRepository(dir);

        InMemoryShardSnapshotSource shard0 = new InMemoryShardSnapshotSource()
                .putFile("segments_1", "hello segment data".getBytes(StandardCharsets.UTF_8))
                .putFile("_0.cfs", "compound file bytes here".getBytes(StandardCharsets.UTF_8));

        Map<ShardId, ShardSnapshotSource> sources = Map.of(new ShardId("myindex", 0), shard0);
        InMemoryRepositoryMetadataSource metadataSource = new InMemoryRepositoryMetadataSource()
                .addIndex("myindex", Map.of("number_of_shards", 1), Map.of("properties", Map.of("field", "text")));

        CreateSnapshotRequest request = CreateSnapshotRequest.of("snap1", List.of("myindex"), sources, metadataSource);
        SnapshotInfo info = repository.createSnapshot(request);

        assertEquals(SnapshotState.SUCCESS, info.state());
        assertEquals(1, info.shardStats().size());
        assertEquals(2, info.shardStats().get("myindex/0").filesCopied());

        InMemoryShardRestoreTarget restoreTarget = new InMemoryShardRestoreTarget();
        Map<ShardId, ShardRestoreTarget> targets = Map.of(new ShardId("myindex", 0), restoreTarget);

        RestoreResult result = repository.restoreSnapshot("snap1", RestoreRequest.all(), targets);
        assertEquals(Map.of("myindex", "myindex"), result.renamedIndices());
        assertEquals(2, restoreTarget.files().size());
        assertEquals("hello segment data", new String(restoreTarget.files().get("segments_1"), StandardCharsets.UTF_8));
        assertEquals("compound file bytes here", new String(restoreTarget.files().get("_0.cfs"), StandardCharsets.UTF_8));

        repository.verify();
    }

    @Test
    public void snapshotStatusTracksFilesCopiedAndBytes() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-status");
        FsRepository repository = newRepository(dir);

        InMemoryShardSnapshotSource shard0 = new InMemoryShardSnapshotSource()
                .putFile("a", new byte[100])
                .putFile("b", new byte[50]);
        Map<ShardId, ShardSnapshotSource> sources = Map.of(new ShardId("idx", 0), shard0);
        CreateSnapshotRequest request = CreateSnapshotRequest.of("s1", List.of("idx"), sources, new InMemoryRepositoryMetadataSource());
        SnapshotInfo info = repository.createSnapshot(request);

        assertEquals(150L, info.shardStats().get("idx/0").bytesCopied());
        assertEquals(2, info.shardStats().get("idx/0").filesCopied());
        assertEquals(0, info.shardStats().get("idx/0").filesReused());
    }

    @Test
    public void cancellationDuringCopyMarksFailedOrPartial() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-cancel");
        FsRepository repository = newRepository(dir);

        InMemoryShardSnapshotSource shard0 = new InMemoryShardSnapshotSource().putFile("a", new byte[10]);
        Map<ShardId, ShardSnapshotSource> sources = Map.of(new ShardId("idx", 0), shard0);
        CancellationToken token = new CancellationToken();
        token.cancel();
        CreateSnapshotRequest request = new CreateSnapshotRequest("cancelled1", List.of("idx"), sources,
                new InMemoryRepositoryMetadataSource(), null, token, null);
        SnapshotInfo info = repository.createSnapshot(request);

        assertEquals(SnapshotState.FAILED, info.state());
        assertEquals("cancelled", info.failureReason());
    }
}
