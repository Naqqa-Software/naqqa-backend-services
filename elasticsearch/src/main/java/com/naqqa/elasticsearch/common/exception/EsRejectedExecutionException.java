package com.naqqa.elasticsearch.common.exception;

public class EsRejectedExecutionException extends ElasticsearchException {

    private final boolean isExecutorShutdown;

    public EsRejectedExecutionException(String msg, boolean isExecutorShutdown, Object... args) {
        super(msg, args);
        this.isExecutorShutdown = isExecutorShutdown;
    }

    public EsRejectedExecutionException(String msg, Object... args) {
        this(msg, false, args);
    }

    public boolean isExecutorShutdown() {
        return isExecutorShutdown;
    }

    @Override
    public RestStatus status() {
        return RestStatus.TOO_MANY_REQUESTS;
    }
}
