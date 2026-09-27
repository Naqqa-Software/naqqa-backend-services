package com.naqqa.elasticsearch.monitor.stats;

import java.util.LinkedHashMap;
import java.util.Map;

public record ShardStats(String index, int shardId, boolean primary, CommonStats stats) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("index", index);
        routing.put("shard", shardId);
        routing.put("primary", primary);
        map.put("routing", routing);
        map.putAll(stats.toMap());
        return map;
    }
}
