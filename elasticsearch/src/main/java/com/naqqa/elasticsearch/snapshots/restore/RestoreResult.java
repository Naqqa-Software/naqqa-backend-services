package com.naqqa.elasticsearch.snapshots.restore;

import java.util.Map;

public record RestoreResult(
        Map<String, String> renamedIndices,
        Map<String, Integer> filesRestoredPerIndex,
        Map<String, Map<String, Object>> restoredIndexSettings) {
}
