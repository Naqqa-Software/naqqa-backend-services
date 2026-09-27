package com.naqqa.elasticsearch.indices.rollover;

import java.util.ArrayList;
import java.util.List;

public final class RolloverService {

    public RolloverResult evaluateConditions(IndexStatsSnapshot stats, RolloverConditions conditions) {
        List<String> matched = new ArrayList<>();
        if (conditions.getMaxAge() != null && stats.ageMillis() >= conditions.getMaxAge().millis()) {
            matched.add("max_age");
        }
        if (conditions.getMaxDocs() != null && stats.docCount() >= conditions.getMaxDocs()) {
            matched.add("max_docs");
        }
        if (conditions.getMaxSize() != null && stats.sizeInBytes() >= conditions.getMaxSize().getBytes()) {
            matched.add("max_size");
        }
        if (conditions.getMaxPrimaryShardSize() != null
            && stats.primaryShardSizeInBytes() >= conditions.getMaxPrimaryShardSize().getBytes()) {
            matched.add("max_primary_shard_size");
        }
        return new RolloverResult(!matched.isEmpty(), matched);
    }
}
