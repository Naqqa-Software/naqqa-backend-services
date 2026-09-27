package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.model.SnapshotState;
import com.naqqa.elasticsearch.test.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class RetentionTest {

    private SnapshotInfo snapshot(String name, Instant startTime) {
        return new SnapshotInfo(name, name, "repo", List.of("idx"), SnapshotState.SUCCESS,
                startTime.toEpochMilli(), startTime.toEpochMilli(), null, "policy1", Map.of(), Map.of(), null);
    }

    @Test
    public void maxCountKeepsOnlyNewestSnapshots() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<SnapshotInfo> snapshots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            snapshots.add(snapshot("s" + i, base.plusSeconds(i * 3600)));
        }
        Retention retention = new Retention(null, 1, 3);
        List<String> toDelete = retention.namesToDelete(snapshots, base.plusSeconds(100000));
        assertEquals(2, toDelete.size());
        assertTrue(toDelete.contains("s0"));
        assertTrue(toDelete.contains("s1"));
        assertFalse(toDelete.contains("s2"));
        assertFalse(toDelete.contains("s3"));
        assertFalse(toDelete.contains("s4"));
    }

    @Test
    public void expireAfterDeletesOldSnapshotsButRespectsMinCount() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<SnapshotInfo> snapshots = List.of(
                snapshot("old1", base),
                snapshot("old2", base.plusSeconds(3600)),
                snapshot("recent", base.plusSeconds(7200)));
        Retention retention = new Retention(Duration.ofHours(2), 2, null);
        Instant now = base.plusSeconds(3 * 3600);
        List<String> toDelete = retention.namesToDelete(snapshots, now);
        assertEquals(1, toDelete.size());
        assertEquals("old1", toDelete.get(0));
    }

    @Test
    public void minCountPreventsDeletionEvenWhenExpired() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<SnapshotInfo> snapshots = List.of(
                snapshot("s1", base),
                snapshot("s2", base.plusSeconds(60)));
        Retention retention = new Retention(Duration.ofSeconds(1), 2, null);
        Instant now = base.plusSeconds(10000);
        List<String> toDelete = retention.namesToDelete(snapshots, now);
        assertEquals(0, toDelete.size());
    }

    @Test
    public void noRetentionRulesDeletesNothing() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<SnapshotInfo> snapshots = List.of(snapshot("s1", base));
        Retention retention = new Retention(null, null, null);
        assertEquals(0, retention.namesToDelete(snapshots, base.plusSeconds(1000000)).size());
    }
}
