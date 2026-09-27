package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;

public class EngineException extends ElasticsearchException {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
