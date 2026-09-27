package com.naqqa.elasticsearch.common.exception;

public class CircuitBreakingException extends ElasticsearchException {

    private final long bytesWanted;
    private final long byteLimit;
    private final String breakerName;

    public CircuitBreakingException(String msg, long bytesWanted, long byteLimit, String breakerName) {
        super(msg);
        this.bytesWanted = bytesWanted;
        this.byteLimit = byteLimit;
        this.breakerName = breakerName;
    }

    public long getBytesWanted() {
        return bytesWanted;
    }

    public long getByteLimit() {
        return byteLimit;
    }

    public String getBreakerName() {
        return breakerName;
    }

    @Override
    public RestStatus status() {
        return RestStatus.TOO_MANY_REQUESTS;
    }
}
