package com.naqqa.elasticsearch.snapshots.restore;

import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.repository.CreateSnapshotRequest;
import com.naqqa.elasticsearch.snapshots.repository.FsRepository;
import com.naqqa.elasticsearch.snapshots.repository.SnapshotException;
import com.naqqa.elasticsearch.snapshots.source.InMemoryRepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.InMemoryShardRestoreTarget;
import com.naqqa.elasticsearch.snapshots.source.InMemoryShardSnapshotSource;
import com.naqqa.elasticsearch.snapshots.source.ShardRestoreTarget;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;

public final class RenamePartialRestoreTest {

    private FsRepository buildRepositoryWithTwoIndices(Path dir) throws IOException {
        FsRepository repository = new FsRepository("repo1", new FsBlobStore(dir));
        InMemoryShardSnapshotSource sourceA = new InMemoryShardSnapshotSource()
                .putFile("a1", "index-a-data".getBytes(StandardCharsets.UTF_8));
        InMemoryShardSnapshotSource sourceB = new InMemoryShardSnapshotSource()
                .putFile("b1", "index-b-data".getBytes(StandardCharsets.UTF_8));
        Map<ShardId, ShardSnapshotSource> sources = new LinkedHashMap<>();
        sources.put(new ShardId("index-a", 0), sourceA);
        sources.put(new ShardId("index-b", 0), sourceB);
        repository.createSnapshot(CreateSnapshotRequest.of("snap1", List.of("index-a", "index-b"), sources,
                new InMemoryRepositoryMetadataSource()));
        return repository;
    }

    @Test
    public void renamePatternAppliesRegexReplacement() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-rename");
        FsRepository repository = buildRepositoryWithTwoIndices(dir);

        RestoreRequest request = new RestoreRequest(List.of("index-a"), "index-(.+)", "restored-$1", Map.of(), Set.of());
        InMemoryShardRestoreTarget targetA = new InMemoryShardRestoreTarget();
        Map<ShardId, ShardRestoreTarget> targets = Map.of(new ShardId("restored-a", 0), targetA);

        RestoreResult result = repository.restoreSnapshot("snap1", request, targets);
        assertEquals("restored-a", result.renamedIndices().get("index-a"));
        assertEquals("index-a-data", new String(targetA.files().get("a1"), StandardCharsets.UTF_8));
    }

    @Test
    public void partialRestoreOnlyRestoresRequestedIndices() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-partial");
        FsRepository repository = buildRepositoryWithTwoIndices(dir);

        RestoreRequest request = RestoreRequest.of(List.of("index-b"));
        InMemoryShardRestoreTarget targetB = new InMemoryShardRestoreTarget();
        Map<ShardId, ShardRestoreTarget> targets = Map.of(new ShardId("index-b", 0), targetB);

        RestoreResult result = repository.restoreSnapshot("snap1", request, targets);
        assertEquals(1, result.renamedIndices().size());
        assertEquals("index-b", result.renamedIndices().get("index-b"));
        assertEquals(1, targetB.files().size());
        assertEquals("index-b-data", new String(targetB.files().get("b1"), StandardCharsets.UTF_8));
    }

    @Test
    public void indexSettingsOverridesAreMergedIntoRestoredSettings() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-overrides");
        FsRepository repository = new FsRepository("repo1", new FsBlobStore(dir));
        InMemoryShardSnapshotSource source = new InMemoryShardSnapshotSource()
                .putFile("f", "data".getBytes(StandardCharsets.UTF_8));
        Map<ShardId, ShardSnapshotSource> sources = Map.of(new ShardId("idx", 0), source);
        InMemoryRepositoryMetadataSource metadataSource = new InMemoryRepositoryMetadataSource()
                .addIndex("idx", Map.of("number_of_replicas", 1, "number_of_shards", 3), Map.of());
        repository.createSnapshot(CreateSnapshotRequest.of("snap1", List.of("idx"), sources, metadataSource));

        RestoreRequest request = new RestoreRequest(List.of(), null, null, Map.of("number_of_replicas", 0), Set.of());
        InMemoryShardRestoreTarget target = new InMemoryShardRestoreTarget();
        RestoreResult result = repository.restoreSnapshot("snap1", request, Map.of(new ShardId("idx", 0), target));

        Map<String, Object> settings = result.restoredIndexSettings().get("idx");
        assertEquals(0, settings.get("number_of_replicas"));
        assertEquals(3L, settings.get("number_of_shards"));
    }

    @Test
    public void restoringIntoExistingIndexNameConflicts() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("snap-conflict");
        FsRepository repository = buildRepositoryWithTwoIndices(dir);

        RestoreRequest request = new RestoreRequest(List.of("index-a"), null, null, Map.of(), Set.of("index-a"));
        assertThrows(SnapshotException.class, () -> repository.restoreSnapshot("snap1", request,
                Map.of(new ShardId("index-a", 0), new InMemoryShardRestoreTarget())));
    }
}
