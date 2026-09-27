package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.common.hash.Murmur3HashFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PreferenceResolver {

    private PreferenceResolver() {
    }

    static ShardRouting resolve(String preference, String localNodeId, ShardId shardId,
                                 List<ShardRouting> candidates, ShardCopySelector fallbackSelector) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (preference == null || preference.isEmpty()) {
            return fallbackSelector.select(shardId, candidates);
        }
        if (preference.equals("_local")) {
            List<ShardRouting> local = filterByNodes(candidates, Set.of(localNodeId));
            List<ShardRouting> pool = local.isEmpty() ? candidates : local;
            return fallbackSelector.select(shardId, pool);
        }
        if (preference.startsWith("_only_nodes:")) {
            Set<String> nodeIds = parseNodeIds(preference.substring("_only_nodes:".length()));
            List<ShardRouting> filtered = filterByNodes(candidates, nodeIds);
            if (filtered.isEmpty()) {
                return null;
            }
            return fallbackSelector.select(shardId, filtered);
        }
        if (preference.startsWith("_prefer_nodes:")) {
            Set<String> nodeIds = parseNodeIds(preference.substring("_prefer_nodes:".length()));
            List<ShardRouting> preferred = filterByNodes(candidates, nodeIds);
            List<ShardRouting> pool = preferred.isEmpty() ? candidates : preferred;
            return fallbackSelector.select(shardId, pool);
        }
        return selectByHash(candidates, preference, shardId);
    }

    private static List<ShardRouting> filterByNodes(List<ShardRouting> candidates, Set<String> nodeIds) {
        List<ShardRouting> result = new ArrayList<>();
        for (ShardRouting candidate : candidates) {
            if (nodeIds.contains(candidate.currentNodeId())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static Set<String> parseNodeIds(String csv) {
        return Set.of(csv.split(","));
    }

    private static ShardRouting selectByHash(List<ShardRouting> candidates, String preference, ShardId shardId) {
        int hash = Murmur3HashFunction.hash(preference + "[" + shardId + "]");
        int idx = Math.floorMod(hash, candidates.size());
        return candidates.get(idx);
    }
}
