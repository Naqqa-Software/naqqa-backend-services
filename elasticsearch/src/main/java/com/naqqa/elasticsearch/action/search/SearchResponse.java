package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.json.JsonValue;
import com.naqqa.elasticsearch.search.execution.TotalHits;

import java.util.List;
import java.util.Map;

public record SearchResponse(List<Hit> hits, TotalHits totalHits, long tookMillis, Shards shards,
                               List<Failure> failures, boolean timedOut, Map<String, Object> aggregations) {

    public record Hit(String index, String id, float score, byte[] source, Object[] sortValues) {

        @SuppressWarnings("unchecked")
        public Map<String, Object> sourceAsMap() {
            if (source == null) {
                return Map.of();
            }
            return (Map<String, Object>) JsonValue.parse(source).toJava();
        }
    }

    public record Shards(int total, int successful, int failed, int skipped) {
    }

    public record Failure(ShardId shardId, String nodeId, String reason) {
    }
}
