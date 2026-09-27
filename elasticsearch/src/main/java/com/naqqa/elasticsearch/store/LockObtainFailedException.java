package com.naqqa.elasticsearch.store;

import java.io.IOException;

public class LockObtainFailedException extends IOException {

    public LockObtainFailedException(String message) {
        super(message);
    }

    public LockObtainFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
