package com.naqqa.elasticsearch.indices;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.RestStatus;

public final class ClosedIndexException extends ElasticsearchException {

    public ClosedIndexException(String index) {
        super("index [{}] is closed", index);
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
