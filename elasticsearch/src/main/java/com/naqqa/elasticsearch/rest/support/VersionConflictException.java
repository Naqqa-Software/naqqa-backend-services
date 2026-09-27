package com.naqqa.elasticsearch.rest.support;

public class VersionConflictException extends RestApiException {

    public VersionConflictException(String message) {
        super(409, message);
    }
}
