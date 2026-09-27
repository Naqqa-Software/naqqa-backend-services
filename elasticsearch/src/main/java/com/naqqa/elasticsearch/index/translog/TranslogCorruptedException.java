package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.RestStatus;

public class TranslogCorruptedException extends ElasticsearchException {

    public TranslogCorruptedException(String message) {
        super(message);
    }

    public TranslogCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public RestStatus status() {
        return RestStatus.INTERNAL_SERVER_ERROR;
    }
}
