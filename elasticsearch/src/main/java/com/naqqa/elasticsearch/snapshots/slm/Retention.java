package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record Retention(Duration expireAfter, Integer minCount, Integer maxCount) {

    public List<String> namesToDelete(List<SnapshotInfo> policySnapshots, Instant now) {
        List<SnapshotInfo> sorted = new ArrayList<>(policySnapshots);
        sorted.sort(Comparator.comparingLong(SnapshotInfo::startTimeMillis));
        int keep = sorted.size();
        int minKeep = minCount == null ? 0 : minCount;
        List<String> deletions = new ArrayList<>();
        for (SnapshotInfo s : sorted) {
            if (keep - 1 < minKeep) {
                break;
            }
            boolean overMax = maxCount != null && keep > maxCount;
            boolean expired = expireAfter != null && now.isAfter(s.startTime().plus(expireAfter));
            if (overMax || expired) {
                deletions.add(s.name());
                keep--;
            }
        }
        return deletions;
    }
}
