package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.IndexRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.IndexShardRoutingTable;
import com.naqqa.elasticsearch.cluster.routing.RoutingTable;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.hash.Murmur3HashFunction;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;

public final class ShardRouter {

    private ShardRouter() {
    }

    public static String effectiveRouting(String id, String routing) {
        return routing != null ? routing : id;
    }

    public static int computeShardId(IndexMetadata indexMetadata, String id, String routing) {
        int numShards = indexMetadata.getNumberOfShards();
        if (numShards <= 0) {
            throw new IllegalStateException("index [" + indexMetadata.getIndex()
                + "] has an invalid number_of_shards [" + numShards + "]");
        }
        int partitionSize = indexMetadata.getSettings().getAsInt("index.routing_partition_size", 1);
        int partitionOffset = 0;
        if (partitionSize > 1) {
            partitionOffset = Math.floorMod(Murmur3HashFunction.hash(id), partitionSize);
        }
        String routingKey = effectiveRouting(id, routing);
        int hash = Murmur3HashFunction.hash(routingKey) + partitionOffset;
        return Math.floorMod(hash, numShards);
    }

    public static IndexMetadata resolveIndexMetadata(ClusterState state, String index) {
        IndexMetadata indexMetadata = state.getMetadata().index(index);
        if (indexMetadata == null) {
            throw new IndexNotFoundException(index);
        }
        return indexMetadata;
    }

    public static ShardId resolveShardId(ClusterState state, String index, String id, String routing) {
        IndexMetadata indexMetadata = resolveIndexMetadata(state, index);
        int shard = computeShardId(indexMetadata, id, routing);
        return new ShardId(index, shard);
    }

    public static IndexShardRoutingTable resolveRoutingTable(ClusterState state, ShardId shardId) {
        RoutingTable routingTable = state.getRoutingTable();
        IndexRoutingTable indexRoutingTable = routingTable.index(shardId.index());
        if (indexRoutingTable == null) {
            throw new IndexNotFoundException(shardId.index());
        }
        IndexShardRoutingTable shardTable = indexRoutingTable.shard(shardId.id());
        if (shardTable == null) {
            throw new IndexNotFoundException(shardId.index());
        }
        return shardTable;
    }

    public static ShardRouting resolvePrimary(ClusterState state, ShardId shardId) {
        IndexShardRoutingTable table = resolveRoutingTable(state, shardId);
        ShardRouting primary = table.primaryShard();
        if (primary == null) {
            throw new IllegalStateException("no primary shard routing entry for " + shardId);
        }
        return primary;
    }
}
