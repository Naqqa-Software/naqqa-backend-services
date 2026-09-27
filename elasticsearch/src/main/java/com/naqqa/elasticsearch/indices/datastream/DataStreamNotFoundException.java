package com.naqqa.elasticsearch.indices.datastream;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.RestStatus;

public final class DataStreamNotFoundException extends ElasticsearchException {

    public DataStreamNotFoundException(String name) {
        super("data stream [{}] not found", name);
    }

    @Override
    public RestStatus status() {
        return RestStatus.NOT_FOUND;
    }
}
