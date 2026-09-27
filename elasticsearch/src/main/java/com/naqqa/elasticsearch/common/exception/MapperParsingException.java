package com.naqqa.elasticsearch.common.exception;

public class MapperParsingException extends ElasticsearchException {

    public MapperParsingException(String msg, Object... args) {
        super(msg, args);
    }

    public MapperParsingException(String msg, Throwable cause, Object... args) {
        super(msg, cause, args);
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
