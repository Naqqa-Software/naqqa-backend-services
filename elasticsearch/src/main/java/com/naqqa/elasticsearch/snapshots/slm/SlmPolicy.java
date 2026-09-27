package com.naqqa.elasticsearch.snapshots.slm;

import java.util.List;

public record SlmPolicy(
        String id,
        String schedule,
        String snapshotNamePattern,
        String repository,
        List<String> indices,
        Retention retention) {

    public SlmPolicy {
        indices = indices == null ? List.of() : List.copyOf(indices);
    }
}
