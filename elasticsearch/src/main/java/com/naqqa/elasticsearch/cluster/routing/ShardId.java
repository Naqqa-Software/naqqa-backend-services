package com.naqqa.elasticsearch.cluster.routing;

public record ShardId(String index, int id) {

    @Override
    public String toString() {
        return "[" + index + "][" + id + "]";
    }
}
