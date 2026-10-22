package com.naqqa.auth.exceptions;

public class ObjectUploadFailed extends RuntimeException {
    public ObjectUploadFailed() {
        super("File upload failed!");
    }

    public ObjectUploadFailed(String message) {
        super(message);
    }

    public ObjectUploadFailed(String message, Throwable cause) {
        super(message, cause);
    }

    public ObjectUploadFailed(Throwable cause) {
        super(cause);
    }
}
