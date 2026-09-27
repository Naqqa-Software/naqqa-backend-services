package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.restore.RestoreRequest;
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

public final class CloneSnapshotTest {

    @Test
    public void cloneCopiesMetadataWithoutCopyingBlobData() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-clone");
        FsRepository repository = new FsRepository("repo1", new FsBlobStore(dir));

        InMemoryShardSnapshotSource source = new InMemoryShardSnapshotSource()
                .putFile("a", "content-a".getBytes(StandardCharsets.UTF_8));
        Map<ShardId, ShardSnapshotSource> sources = Map.of(new ShardId("idx", 0), source);
        repository.createSnapshot(CreateSnapshotRequest.of("original", List.of("idx"), sources, new InMemoryRepositoryMetadataSource()));

        long blobsBeforeClone = countBlobFiles(dir.resolve("data"));

        SnapshotInfo cloned = repository.cloneSnapshot("original", "cloned");
        assertEquals("cloned", cloned.name());

        long blobsAfterClone = countBlobFiles(dir.resolve("data"));
        assertEquals(blobsBeforeClone, blobsAfterClone);

        InMemoryShardRestoreTarget restoreTarget = new InMemoryShardRestoreTarget();
        Map<ShardId, ShardRestoreTarget> targets = Map.of(new ShardId("idx", 0), restoreTarget);
        repository.restoreSnapshot("cloned", RestoreRequest.all(), targets);
        assertEquals("content-a", new String(restoreTarget.files().get("a"), StandardCharsets.UTF_8));

        repository.deleteSnapshot("original");
        long blobsAfterDeletingOriginal = countBlobFiles(dir.resolve("data"));
        assertEquals(blobsBeforeClone, blobsAfterDeletingOriginal);

        InMemoryShardRestoreTarget restoreTarget2 = new InMemoryShardRestoreTarget();
        repository.restoreSnapshot("cloned", RestoreRequest.all(), Map.of(new ShardId("idx", 0), restoreTarget2));
        assertEquals("content-a", new String(restoreTarget2.files().get("a"), StandardCharsets.UTF_8));
    }

    private static long countBlobFiles(Path dataDir) throws IOException {
        try (var files = java.nio.file.Files.list(dataDir)) {
            return files.filter(p -> !p.getFileName().toString().endsWith(".refcount")).count();
        }
    }
}
