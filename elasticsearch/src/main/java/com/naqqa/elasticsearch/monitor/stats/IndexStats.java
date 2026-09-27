package com.naqqa.elasticsearch.monitor.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record IndexStats(String index, CommonStats primaries, CommonStats total, List<ShardStats> shards) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("primaries", primaries.toMap());
        map.put("total", total.toMap());
        Map<String, Object> shardsByIndex = new LinkedHashMap<>();
        List<Map<String, Object>> shardMaps = new ArrayList<>();
        for (ShardStats shard : shards) {
            shardMaps.add(shard.toMap());
        }
        shardsByIndex.put(index, shardMaps);
        map.put("shards", shardsByIndex);
        return map;
    }
}
