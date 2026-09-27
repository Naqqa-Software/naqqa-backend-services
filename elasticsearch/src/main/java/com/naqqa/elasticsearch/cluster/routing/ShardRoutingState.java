package com.naqqa.elasticsearch.cluster.routing;

public enum ShardRoutingState {
    UNASSIGNED,
    INITIALIZING,
    STARTED,
    RELOCATING
}
