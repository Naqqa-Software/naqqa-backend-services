package com.naqqa.elasticsearch.common.exception;

public class ElasticsearchSecurityException extends ElasticsearchException {

    private final RestStatus status;

    public ElasticsearchSecurityException(String msg, RestStatus status, Object... args) {
        super(msg, args);
        this.status = status;
    }

    public ElasticsearchSecurityException(String msg, Object... args) {
        this(msg, RestStatus.FORBIDDEN, args);
    }

    @Override
    public RestStatus status() {
        return status;
    }
}
