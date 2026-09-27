package com.naqqa.elasticsearch.common.exception;

public class ClusterBlockException extends ElasticsearchException {

    public ClusterBlockException(String msg, Object... args) {
        super(msg, args);
    }

    @Override
    public RestStatus status() {
        return RestStatus.SERVICE_UNAVAILABLE;
    }
}
