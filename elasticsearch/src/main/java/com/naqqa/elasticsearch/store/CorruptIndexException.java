package com.naqqa.elasticsearch.store;

import java.io.IOException;

public class CorruptIndexException extends IOException {

    private final String originalMessage;
    private final String resourceDescription;

    public CorruptIndexException(String message, DataInput input) {
        this(message, String.valueOf(input), null);
    }

    public CorruptIndexException(String message, DataOutput output) {
        this(message, String.valueOf(output), null);
    }

    public CorruptIndexException(String message, DataInput input, Throwable cause) {
        this(message, String.valueOf(input), cause);
    }

    public CorruptIndexException(String message, String resourceDescription) {
        this(message, resourceDescription, null);
    }

    public CorruptIndexException(String message, String resourceDescription, Throwable cause) {
        super(message + " (resource=" + resourceDescription + ")", cause);
        this.originalMessage = message;
        this.resourceDescription = resourceDescription;
    }

    public String getOriginalMessage() {
        return originalMessage;
    }

    public String getResourceDescription() {
        return resourceDescription;
    }
}
