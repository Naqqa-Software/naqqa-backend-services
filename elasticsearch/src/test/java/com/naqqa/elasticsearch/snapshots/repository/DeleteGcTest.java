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
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class DeleteGcTest {

    @Test
    public void deletingSnapshotGcsOrphanBlobsButKeepsSharedOnes() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-gc");
        FsRepository repository = new FsRepository("repo1", new FsBlobStore(dir));

        InMemoryShardSnapshotSource sourceA = new InMemoryShardSnapshotSource()
                .putFile("shared", "shared-bytes".getBytes(StandardCharsets.UTF_8))
                .putFile("onlyA", "only-a-bytes".getBytes(StandardCharsets.UTF_8));
        Map<ShardId, ShardSnapshotSource> sourcesA = Map.of(new ShardId("idx", 0), sourceA);
        repository.createSnapshot(CreateSnapshotRequest.of("snap-a", List.of("idx"), sourcesA, new InMemoryRepositoryMetadataSource()));

        InMemoryShardSnapshotSource sourceB = new InMemoryShardSnapshotSource()
                .putFile("shared", "shared-bytes".getBytes(StandardCharsets.UTF_8))
                .putFile("onlyB", "only-b-bytes".getBytes(StandardCharsets.UTF_8));
        Map<ShardId, ShardSnapshotSource> sourcesB = Map.of(new ShardId("idx", 0), sourceB);
        repository.createSnapshot(CreateSnapshotRequest.of("snap-b", List.of("idx"), sourcesB, new InMemoryRepositoryMetadataSource()));

        Path dataDir = dir.resolve("data");
        long blobsBeforeDelete = countBlobFiles(dataDir);
        assertEquals(3, blobsBeforeDelete);

        repository.deleteSnapshot("snap-a");

        long blobsAfterDelete = countBlobFiles(dataDir);
        assertEquals(2, blobsAfterDelete);

        String sharedHash = sha256("shared-bytes".getBytes(StandardCharsets.UTF_8));
        String onlyAHash = sha256("only-a-bytes".getBytes(StandardCharsets.UTF_8));
        assertTrue(java.nio.file.Files.exists(dataDir.resolve(sharedHash)), "shared blob should remain");
        assertFalse(java.nio.file.Files.exists(dataDir.resolve(onlyAHash)), "orphan blob should be gc'd");

        assertEquals(1, repository.listSnapshots().size());
        assertEquals("snap-b", repository.listSnapshots().get(0).name());
    }

    private static long countBlobFiles(Path dataDir) throws IOException {
        try (var files = java.nio.file.Files.list(dataDir)) {
            return files.filter(p -> !p.getFileName().toString().endsWith(".refcount")).count();
        }
    }

    private static String sha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
