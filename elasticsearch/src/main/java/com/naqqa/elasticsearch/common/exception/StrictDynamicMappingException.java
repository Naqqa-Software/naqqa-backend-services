package com.naqqa.elasticsearch.common.exception;

public class StrictDynamicMappingException extends ElasticsearchException {

    public StrictDynamicMappingException(String path, String fieldName) {
        super("mapping set to strict, dynamic introduction of [{}] within [{}] is not allowed", fieldName, path);
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
