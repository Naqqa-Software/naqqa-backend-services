package com.naqqa.elasticsearch.common.settings;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.RestStatus;

public class SettingsException extends ElasticsearchException {

    public SettingsException(String msg, Object... args) {
        super(msg, args);
    }

    public SettingsException(String msg, Throwable cause) {
        super(msg, cause);
    }

    @Override
    public RestStatus status() {
        return RestStatus.INTERNAL_SERVER_ERROR;
    }
}
