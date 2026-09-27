package com.naqqa.elasticsearch.ingest;

public class IngestProcessorException extends RuntimeException {

    private final String processorType;
    private final String processorTag;

    public IngestProcessorException(String processorType, String processorTag, Throwable cause) {
        super("[" + processorType + (processorTag != null ? "/" + processorTag : "") + "] " + cause.getMessage(), cause);
        this.processorType = processorType;
        this.processorTag = processorTag;
    }

    public String getProcessorType() {
        return processorType;
    }

    public String getProcessorTag() {
        return processorTag;
    }
}
