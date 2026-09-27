package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.index.replication.ReplicationGroup;

import java.io.IOException;

@FunctionalInterface
public interface ShardWriteOperation<T> {

    T execute(ReplicationGroup group) throws IOException;
}
