package com.naqqa.elasticsearch.rest.support;

public class DocumentMissingException extends RestApiException {

    public DocumentMissingException(String index, String id) {
        super(404, "[" + id + "]: document missing");
    }
}
