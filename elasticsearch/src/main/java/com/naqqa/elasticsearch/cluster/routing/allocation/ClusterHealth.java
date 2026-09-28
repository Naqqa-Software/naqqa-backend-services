package com.naqqa.elasticsearch.cluster.routing.allocation;

import com.naqqa.elasticsearch.cluster.node.DiscoveryNode;
import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ClusterHealth {

    public enum Status {
        GREEN, YELLOW, RED
    }

    private ClusterHealth() {
    }

    public static Map<String, Object> compute(ClusterState clusterState) {
        Map<String, Object> result = new LinkedHashMap<>();
        int numberOfNodes = clusterState.getNodes().size();
        int numberOfDataNodes = clusterState.getNodes().getDataNodes().size();
        int activePrimaryShards = 0;
        int activeShards = 0;
        int relocatingShards = 0;
        int initializingShards = 0;
        int unassignedShards = 0;
        Status overall = Status.GREEN;

        Map<String, Object> indices = new LinkedHashMap<>();
        for (IndexMetadata imd : clusterState.getMetadata().getIndices().values()) {
            IndexRoutingTable indexRouting = clusterState.getRoutingTable().index(imd.getIndex());
            Status indexStatus = Status.GREEN;
            int indexActivePrimary = 0;
            int indexActive = 0;
            int indexRelocating = 0;
            int indexInitializing = 0;
            int indexUnassigned = 0;
            if (indexRouting != null) {
                for (IndexShardRoutingTable shardTable : indexRouting.getShards().values()) {
                    boolean primaryActive = false;
                    boolean anyReplicaNotActive = false;
                    for (ShardRouting shard : shardTable.getShards()) {
                        boolean onKnownNode = shard.currentNodeId() != null && clusterState.getNodes().nodeExists(shard.currentNodeId());
                        if (shard.active() && onKnownNode) {
                            indexActive++;
                            activeShards++;
                            if (shard.primary()) {
                                indexActivePrimary++;
                                activePrimaryShards++;
                                primaryActive = true;
                            }
                        } else if (!shard.primary()) {
                            anyReplicaNotActive = true;
                        }
                        if (shard.relocating()) {
                            indexRelocating++;
                            relocatingShards++;
                        }
                        if (shard.initializing()) {
                            indexInitializing++;
                            initializingShards++;
                        }
                        if (shard.unassigned()) {
                            indexUnassigned++;
                            unassignedShards++;
                        }
                    }
                    if (!primaryActive) {
                        indexStatus = Status.RED;
                    } else if (anyReplicaNotActive && indexStatus != Status.RED) {
                        indexStatus = Status.YELLOW;
                    }
                }
            } else {
                indexStatus = Status.RED;
            }
            if (indexStatus == Status.RED) {
                overall = Status.RED;
            } else if (indexStatus == Status.YELLOW && overall != Status.RED) {
                overall = Status.YELLOW;
            }
            Map<String, Object> indexHealth = new LinkedHashMap<>();
            indexHealth.put("status", indexStatus.name().toLowerCase(Locale.ROOT));
            indexHealth.put("number_of_shards", imd.getNumberOfShards());
            indexHealth.put("number_of_replicas", imd.getNumberOfReplicas());
            indexHealth.put("active_primary_shards", indexActivePrimary);
            indexHealth.put("active_shards", indexActive);
            indexHealth.put("relocating_shards", indexRelocating);
            indexHealth.put("initializing_shards", indexInitializing);
            indexHealth.put("unassigned_shards", indexUnassigned);
            indices.put(imd.getIndex(), indexHealth);
        }

        result.put("cluster_name", clusterState.getClusterName());
        result.put("status", overall.name().toLowerCase(Locale.ROOT));
        result.put("number_of_nodes", numberOfNodes);
        result.put("number_of_data_nodes", numberOfDataNodes);
        result.put("active_primary_shards", activePrimaryShards);
        result.put("active_shards", activeShards);
        result.put("relocating_shards", relocatingShards);
        result.put("initializing_shards", initializingShards);
        result.put("unassigned_shards", unassignedShards);
        result.put("indices", indices);
        return result;
    }
}
