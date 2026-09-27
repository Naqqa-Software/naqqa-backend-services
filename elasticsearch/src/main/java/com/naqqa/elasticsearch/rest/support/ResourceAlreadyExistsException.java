package com.naqqa.elasticsearch.rest.support;

public class ResourceAlreadyExistsException extends RestApiException {

    public ResourceAlreadyExistsException(String message) {
        super(400, message);
    }
}
