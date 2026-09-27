package com.naqqa.elasticsearch.common.exception;

public class SearchPhaseExecutionException extends ElasticsearchException {

    private final String phaseName;

    public SearchPhaseExecutionException(String phaseName, String msg, Throwable cause) {
        super(msg, cause);
        this.phaseName = phaseName;
    }

    public SearchPhaseExecutionException(String phaseName, String msg, Object... args) {
        super(msg, args);
        this.phaseName = phaseName;
    }

    public String getPhaseName() {
        return phaseName;
    }

    @Override
    public RestStatus status() {
        Throwable cause = getCause();
        return cause != null ? ExceptionsHelper.status(cause) : RestStatus.SERVICE_UNAVAILABLE;
    }
}
