package com.naqqa.elasticsearch.cluster.routing.allocation.decider;

import com.naqqa.elasticsearch.cluster.routing.RoutingNode;
import com.naqqa.elasticsearch.cluster.routing.ShardRouting;
import com.naqqa.elasticsearch.cluster.routing.UnassignedInfo;
import com.naqqa.elasticsearch.cluster.routing.allocation.AllocationDecider;
import com.naqqa.elasticsearch.cluster.routing.allocation.Decision;
import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import com.naqqa.elasticsearch.cluster.state.Settings;

import java.util.Locale;

public final class EnableAllocationDecider implements AllocationDecider {

    public enum Enable {
        ALL, PRIMARIES, NEW_PRIMARIES, NONE
    }

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        Enable enable = resolve(allocation, shardRouting.getIndex());
        return switch (enable) {
            case ALL -> Decision.YES;
            case NONE -> Decision.single(Decision.Type.NO, name(), "allocation is disabled (enable=none)");
            case PRIMARIES -> shardRouting.primary() ? Decision.YES
                : Decision.single(Decision.Type.NO, name(), "allocation of replicas is disabled (enable=primaries)");
            case NEW_PRIMARIES -> {
                if (!shardRouting.primary()) {
                    yield Decision.single(Decision.Type.NO, name(), "allocation of replicas is disabled (enable=new_primaries)");
                }
                UnassignedInfo info = shardRouting.unassignedInfo();
                boolean isNew = info != null && (info.getReason() == UnassignedInfo.Reason.INDEX_CREATED
                    || info.getReason() == UnassignedInfo.Reason.CLUSTER_RECOVERED);
                yield isNew ? Decision.YES
                    : Decision.single(Decision.Type.NO, name(), "only new primaries may be allocated (enable=new_primaries)");
            }
        };
    }

    private Enable resolve(RoutingAllocation allocation, String index) {
        String indexValue = allocation.indexSettings(index).get("index.routing.allocation.enable");
        if (indexValue != null) {
            return Enable.valueOf(indexValue.toUpperCase(Locale.ROOT));
        }
        String clusterValue = allocation.clusterSettings().get("cluster.routing.allocation.enable", "all");
        return Enable.valueOf(clusterValue.toUpperCase(Locale.ROOT));
    }
}
