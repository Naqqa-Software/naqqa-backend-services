package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.RestStatusProvider;

public final class HttpParseException extends RuntimeException implements RestStatusProvider {

    private final int status;

    public HttpParseException(int status, String message) {
        super(message);
        this.status = status;
    }

    @Override
    public int restStatus() {
        return status;
    }
}
