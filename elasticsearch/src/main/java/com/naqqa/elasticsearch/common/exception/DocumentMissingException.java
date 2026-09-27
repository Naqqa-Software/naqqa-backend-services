package com.naqqa.elasticsearch.common.exception;

public class DocumentMissingException extends ElasticsearchException {

    public DocumentMissingException(String index, String id) {
        super("[{}]: document missing", id);
        addMetadata("index", index);
        addMetadata("id", id);
    }

    @Override
    public RestStatus status() {
        return RestStatus.NOT_FOUND;
    }
}
