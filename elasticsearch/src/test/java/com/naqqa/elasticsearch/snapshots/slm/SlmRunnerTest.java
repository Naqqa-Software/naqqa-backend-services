package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.snapshots.blobstore.FsBlobStore;
import com.naqqa.elasticsearch.snapshots.model.ShardId;
import com.naqqa.elasticsearch.snapshots.repository.FsRepository;
import com.naqqa.elasticsearch.snapshots.repository.Repository;
import com.naqqa.elasticsearch.snapshots.source.InMemoryRepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.InMemoryShardSnapshotSource;
import com.naqqa.elasticsearch.snapshots.source.RepositoryMetadataSource;
import com.naqqa.elasticsearch.snapshots.source.ShardSnapshotSource;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SlmRunnerTest {

    private static final class FixedSnapshotSource implements SlmSnapshotSource {
        @Override
        public List<String> indices() {
            return List.of("idx");
        }

        @Override
        public Map<ShardId, ShardSnapshotSource> shardSources() {
            return Map.of(new ShardId("idx", 0),
                    new InMemoryShardSnapshotSource().putFile("f", "data".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public RepositoryMetadataSource metadataSource() {
            return new InMemoryRepositoryMetadataSource();
        }
    }

    @Test
    public void tickDoesNothingBeforeScheduleIsDue() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("slm-nofire");
        Repository repository = new FsRepository("repo1", new FsBlobStore(dir));
        SlmRunner runner = new SlmRunner(Map.of("repo1", repository));

        SlmPolicy policy = new SlmPolicy("p1", "interval:PT1H", "<snap-{now{yyyy.MM.dd-HH.mm.ss}}>", "repo1",
                List.of("idx"), new Retention(null, 1, 5));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        runner.addPolicy(policy, new FixedSnapshotSource(), t0);

        List<SlmRunner.SlmTriggerResult> triggered = runner.tick(t0.plusSeconds(1800));
        assertTrue(triggered.isEmpty());
        assertEquals(0, repository.listSnapshots().size());
    }

    @Test
    public void tickTriggersSnapshotOnceClockPassesNextFireTime() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("slm-fire");
        Repository repository = new FsRepository("repo1", new FsBlobStore(dir));
        SlmRunner runner = new SlmRunner(Map.of("repo1", repository));

        SlmPolicy policy = new SlmPolicy("p1", "interval:PT1H", "<snap-{now{yyyy.MM.dd-HH.mm.ss}}>", "repo1",
                List.of("idx"), new Retention(null, 1, 5));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        runner.addPolicy(policy, new FixedSnapshotSource(), t0);

        List<SlmRunner.SlmTriggerResult> triggered = runner.tick(t0.plusSeconds(3700));
        assertEquals(1, triggered.size());
        assertEquals("p1", triggered.get(0).policyId());
        assertEquals(1, repository.listSnapshots().size());
        assertEquals("p1", repository.listSnapshots().get(0).policyId());
    }

    @Test
    public void retentionPrunesToMaxCountAcrossRepeatedTicks() throws IOException {
        Path dir = java.nio.file.Files.createTempDirectory("slm-retention");
        Repository repository = new FsRepository("repo1", new FsBlobStore(dir));
        SlmRunner runner = new SlmRunner(Map.of("repo1", repository));

        SlmPolicy policy = new SlmPolicy("p1", "interval:PT1H", "<snap-{now{yyyy.MM.dd-HH.mm.ss}}>", "repo1",
                List.of("idx"), new Retention(null, 1, 3));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        runner.addPolicy(policy, new FixedSnapshotSource(), t0);

        Instant now = t0;
        for (int i = 0; i < 5; i++) {
            now = now.plusSeconds(3700);
            runner.tick(now);
        }

        List<String> created = runner.createdSnapshots("p1");
        assertEquals(5, created.size());

        List<String> remainingNames = repository.listSnapshots().stream().map(s -> s.name()).toList();
        assertEquals(3, remainingNames.size());
        assertFalse(remainingNames.contains(created.get(0)));
        assertFalse(remainingNames.contains(created.get(1)));
        assertTrue(remainingNames.contains(created.get(2)));
        assertTrue(remainingNames.contains(created.get(3)));
        assertTrue(remainingNames.contains(created.get(4)));
    }
}
