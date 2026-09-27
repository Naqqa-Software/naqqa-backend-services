package com.naqqa.elasticsearch.cluster.routing.allocation.command;

import com.naqqa.elasticsearch.cluster.routing.allocation.RoutingAllocation;

public interface RerouteCommand {

    void execute(RoutingAllocation allocation);
}
