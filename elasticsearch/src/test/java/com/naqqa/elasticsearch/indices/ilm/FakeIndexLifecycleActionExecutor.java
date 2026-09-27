package com.naqqa.elasticsearch.indices.ilm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FakeIndexLifecycleActionExecutor implements IndexLifecycleActionExecutor {

    public final List<String> calls = new ArrayList<>();
    public final Set<String> readOnlyIndices = new HashSet<>();
    public final Set<String> deletedIndices = new HashSet<>();
    private final Set<String> failOnceOnForceMerge = new HashSet<>();

    public void failNextForceMerge(String index) {
        failOnceOnForceMerge.add(index);
    }

    @Override
    public String rolloverIndex(String index) {
        calls.add("rollover:" + index);
        return index + "-rolled";
    }

    @Override
    public String shrinkIndex(String index, int numberOfShards) {
        calls.add("shrink:" + index + ":" + numberOfShards);
        return index + "-shrunk";
    }

    @Override
    public void forceMergeIndex(String index, int maxNumSegments) {
        if (failOnceOnForceMerge.remove(index)) {
            throw new RuntimeException("simulated forcemerge failure for [" + index + "]");
        }
        calls.add("forcemerge:" + index + ":" + maxNumSegments);
    }

    @Override
    public void setIndexReadOnly(String index, boolean readOnly) {
        calls.add("readonly:" + index + ":" + readOnly);
        if (readOnly) {
            readOnlyIndices.add(index);
        } else {
            readOnlyIndices.remove(index);
        }
    }

    @Override
    public void allocateIndex(String index, Map<String, String> routingSettings, Integer numberOfReplicas) {
        calls.add("allocate:" + index + ":" + routingSettings);
    }

    @Override
    public void deleteIndex(String index) {
        calls.add("delete:" + index);
        deletedIndices.add(index);
    }

    @Override
    public void setIndexPriority(String index, int priority) {
        calls.add("priority:" + index + ":" + priority);
    }
}
