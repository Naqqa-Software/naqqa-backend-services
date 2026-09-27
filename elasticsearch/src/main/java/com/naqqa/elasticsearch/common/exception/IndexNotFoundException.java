package com.naqqa.elasticsearch.common.exception;

public class IndexNotFoundException extends ElasticsearchException {

    private final String index;

    public IndexNotFoundException(String index) {
        super("no such index [{}]", index);
        this.index = index;
        addMetadata("index", index);
    }

    public IndexNotFoundException(String index, Throwable cause) {
        super("no such index [{}]", cause, index);
        this.index = index;
        addMetadata("index", index);
    }

    public String getIndex() {
        return index;
    }

    @Override
    public RestStatus status() {
        return RestStatus.NOT_FOUND;
    }
}
