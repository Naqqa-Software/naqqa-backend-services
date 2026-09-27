package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.index.replication.PrimarySteppedDownException;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;
import com.naqqa.elasticsearch.index.replication.StalePrimaryException;
import com.naqqa.elasticsearch.rest.support.IndexNotFoundException;

import java.io.IOException;
import java.util.Map;

public final class RelocationAwareRouter {

    public static final int DEFAULT_MAX_RETRIES = 1;

    private final ClusterStateSupplier clusterStateSupplier;
    private final Map<ShardId, ReplicationGroup> replicationGroups;
    private final int maxRetries;
    private final RelocationRetryListener retryListener;

    public RelocationAwareRouter(ClusterStateSupplier clusterStateSupplier, Map<ShardId, ReplicationGroup> replicationGroups) {
        this(clusterStateSupplier, replicationGroups, DEFAULT_MAX_RETRIES, null);
    }

    public RelocationAwareRouter(ClusterStateSupplier clusterStateSupplier, Map<ShardId, ReplicationGroup> replicationGroups,
                                  int maxRetries) {
        this(clusterStateSupplier, replicationGroups, maxRetries, null);
    }

    public RelocationAwareRouter(ClusterStateSupplier clusterStateSupplier, Map<ShardId, ReplicationGroup> replicationGroups,
                                  int maxRetries, RelocationRetryListener retryListener) {
        this.clusterStateSupplier = clusterStateSupplier;
        this.replicationGroups = replicationGroups;
        this.maxRetries = maxRetries;
        this.retryListener = retryListener;
    }

    public ClusterStateSupplier clusterStateSupplier() {
        return clusterStateSupplier;
    }

    public Map<ShardId, ReplicationGroup> replicationGroups() {
        return replicationGroups;
    }

    public ShardId resolveShardId(String index, String id, String routing) {
        return ShardRouter.resolveShardId(clusterStateSupplier.getClusterState(), index, id, routing);
    }

    public IndexNameResolver.Resolution resolveIndex(String indexOrAlias) {
        return IndexNameResolver.resolveForWrite(clusterStateSupplier.getClusterState(), indexOrAlias);
    }

    public ReplicationGroup groupFor(ShardId shardId) {
        ReplicationGroup group = replicationGroups.get(shardId);
        if (group == null) {
            throw new IndexNotFoundException(shardId.index());
        }
        return group;
    }

    public <T> T execute(String index, String id, String routing, ShardWriteOperation<T> operation) throws IOException {
        ShardId shardId = resolveShardId(index, id, routing);
        ReplicationGroup group = groupFor(shardId);
        int attempt = 0;
        while (true) {
            try {
                return operation.execute(group);
            } catch (StalePrimaryException | PrimarySteppedDownException relocationException) {
                if (attempt >= maxRetries) {
                    throw relocationException;
                }
                attempt++;
                if (retryListener != null) {
                    retryListener.onRelocationDetected(shardId, relocationException);
                }
                ClusterState freshState = clusterStateSupplier.getClusterState();
                shardId = ShardRouter.resolveShardId(freshState, index, id, routing);
                group = groupFor(shardId);
            }
        }
    }
}
