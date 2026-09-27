package com.naqqa.elasticsearch.common.exception;

public class VersionConflictEngineException extends ElasticsearchException {

    public VersionConflictEngineException(String index, String id, String message, Object... args) {
        super(message, args);
        addMetadata("index", index);
        addMetadata("id", id);
    }

    public VersionConflictEngineException(String index, String id, long currentVersion, long expectedVersion) {
        super(
            "[{}]: version conflict, current version [{}] is different than the one provided [{}]",
            id,
            currentVersion,
            expectedVersion
        );
        addMetadata("index", index);
        addMetadata("id", id);
    }

    @Override
    public RestStatus status() {
        return RestStatus.CONFLICT;
    }
}
