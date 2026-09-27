package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.cluster.state.ClusterState;
import com.naqqa.elasticsearch.transport.Connection;

import java.io.IOException;

@FunctionalInterface
public interface RemoteShardConnector {

    Connection connect(ShardId shardId, ClusterState state) throws IOException;
}
