package com.naqqa.elasticsearch.common.exception;

public class ResourceAlreadyExistsException extends ElasticsearchException {

    public ResourceAlreadyExistsException(String message, Object... args) {
        super(message, args);
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
