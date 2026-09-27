package com.naqqa.elasticsearch.snapshots.restore;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record RestoreRequest(
        List<String> indices,
        String renamePattern,
        String renameReplacement,
        Map<String, Object> indexSettingsOverrides,
        Set<String> existingIndices) {

    public RestoreRequest {
        indices = indices == null ? List.of() : List.copyOf(indices);
        indexSettingsOverrides = indexSettingsOverrides == null ? Map.of() : Map.copyOf(indexSettingsOverrides);
        existingIndices = existingIndices == null ? Set.of() : Set.copyOf(existingIndices);
    }

    public static RestoreRequest all() {
        return new RestoreRequest(List.of(), null, null, Map.of(), Set.of());
    }

    public static RestoreRequest of(List<String> indices) {
        return new RestoreRequest(indices, null, null, Map.of(), Set.of());
    }

    public boolean includesAll() {
        return indices.isEmpty();
    }
}
