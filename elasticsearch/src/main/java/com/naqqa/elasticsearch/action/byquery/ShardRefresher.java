package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.action.write.RelocationAwareRouter;
import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.index.replication.ReplicationGroup;

import java.io.IOException;
import java.util.Map;

final class ShardRefresher {

    private ShardRefresher() {
    }

    static void refreshIndex(RelocationAwareRouter router, String resolvedIndex) throws IOException {
        for (Map.Entry<ShardId, ReplicationGroup> entry : router.replicationGroups().entrySet()) {
            if (entry.getKey().index().equals(resolvedIndex)) {
                entry.getValue().primary().indexShard().refresh();
            }
        }
    }
}
