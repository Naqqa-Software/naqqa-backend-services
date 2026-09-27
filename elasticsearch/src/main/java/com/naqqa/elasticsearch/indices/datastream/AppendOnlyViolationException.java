package com.naqqa.elasticsearch.indices.datastream;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.RestStatus;

public final class AppendOnlyViolationException extends ElasticsearchException {

    public AppendOnlyViolationException(String message, Object... args) {
        super(message, args);
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
