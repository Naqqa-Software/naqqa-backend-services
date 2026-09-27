package com.naqqa.elasticsearch.rest.support;

public class IndexNotFoundException extends RestApiException {

    public IndexNotFoundException(String index) {
        super(404, "no such index [" + index + "]");
    }
}
