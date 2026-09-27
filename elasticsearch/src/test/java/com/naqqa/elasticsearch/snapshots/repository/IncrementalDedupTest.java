package com.naqqa.elasticsearch.snapshots.repository;

import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.source.InMemoryRepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.InMemoryShardSnapshotSource;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class IncrementalDedupTest {

    @Test
    public void secondSnapshotWithMostlyIdenticalFilesDedupesBlobs() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-dedup");
        FsRepository repository = new FsRepository("repo1", new FsBlobStore(dir));

        InMemoryShardSnapshotSource sourceA = new InMemoryShardSnapshotSource()
                .putFile("f1", "unchanged-1".getBytes(StandardCharsets.UTF_8))
                .putFile("f2", "unchanged-2".getBytes(StandardCharsets.UTF_8))
                .putFile("f3", "unchanged-3".getBytes(StandardCharsets.UTF_8))
                .putFile("f4", "unchanged-4".getBytes(StandardCharsets.UTF_8))
                .putFile("f5", "unchanged-5".getBytes(StandardCharsets.UTF_8))
                .putFile("f6", "unchanged-6".getBytes(StandardCharsets.UTF_8))
                .putFile("f7", "unchanged-7".getBytes(StandardCharsets.UTF_8))
                .putFile("f8", "unchanged-8".getBytes(StandardCharsets.UTF_8))
                .putFile("f9", "unchanged-9".getBytes(StandardCharsets.UTF_8))
                .putFile("f10", "changed-original".getBytes(StandardCharsets.UTF_8));

        Map<ShardId, ShardSnapshotSource> sourcesA = Map.of(new ShardId("idx", 0), sourceA);
        repository.createSnapshot(CreateSnapshotRequest.of("snap-a", List.of("idx"), sourcesA, new InMemoryRepositoryMetadataSource()));

        long blobCountAfterFirst = countDataBlobs(dir);

        InMemoryShardSnapshotSource sourceB = new InMemoryShardSnapshotSource()
                .putFile("f1", "unchanged-1".getBytes(StandardCharsets.UTF_8))
                .putFile("f2", "unchanged-2".getBytes(StandardCharsets.UTF_8))
                .putFile("f3", "unchanged-3".getBytes(StandardCharsets.UTF_8))
                .putFile("f4", "unchanged-4".getBytes(StandardCharsets.UTF_8))
                .putFile("f5", "unchanged-5".getBytes(StandardCharsets.UTF_8))
                .putFile("f6", "unchanged-6".getBytes(StandardCharsets.UTF_8))
                .putFile("f7", "unchanged-7".getBytes(StandardCharsets.UTF_8))
                .putFile("f8", "unchanged-8".getBytes(StandardCharsets.UTF_8))
                .putFile("f9", "unchanged-9".getBytes(StandardCharsets.UTF_8))
                .putFile("f10", "changed-updated".getBytes(StandardCharsets.UTF_8));

        Map<ShardId, ShardSnapshotSource> sourcesB = Map.of(new ShardId("idx", 0), sourceB);
        var infoB = repository.createSnapshot(CreateSnapshotRequest.of("snap-b", List.of("idx"), sourcesB, new InMemoryRepositoryMetadataSource()));

        assertEquals(9, infoB.shardStats().get("idx/0").filesReused());
        assertEquals(1, infoB.shardStats().get("idx/0").filesCopied());

        long blobCountAfterSecond = countDataBlobs(dir);
        assertEquals(blobCountAfterFirst + 1, blobCountAfterSecond);
    }

    private static long countDataBlobs(Path repoRoot) throws IOException {
        Path dataDir = repoRoot.resolve("data");
        if (!java.nio.file.Files.isDirectory(dataDir)) {
            return 0;
        }
        try (var files = java.nio.file.Files.list(dataDir)) {
            return files.filter(p -> !p.getFileName().toString().endsWith(".refcount")).count();
        }
    }
}
