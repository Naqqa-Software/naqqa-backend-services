package com.naqqa.elasticsearch.rest.support;

import com.naqqa.elasticsearch.http.RestStatusProvider;

public class RestApiException extends RuntimeException implements RestStatusProvider {

    private final int status;

    public RestApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public RestApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    @Override
    public int restStatus() {
        return status;
    }
}
