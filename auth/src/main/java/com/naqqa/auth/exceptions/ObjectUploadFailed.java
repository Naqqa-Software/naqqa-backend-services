package com.naqqa.auth.exceptions;

public class ObjectUploadFailed extends RuntimeException {
    // Constructor with no arguments
    public ObjectUploadFailed() {
        super("File upload failed!");
    }

    // Constructor with a custom message
    public ObjectUploadFailed(String message) {
        super(message);
    }

    // Constructor with custom message and cause
    public ObjectUploadFailed(String message, Throwable cause) {
        super(message, cause);
    }

    // Constructor with a cause
    public ObjectUploadFailed(Throwable cause) {
        super(cause);
    }
}
