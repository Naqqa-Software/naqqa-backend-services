package com.naqqa.filestorage.exceptions;

public class GCPFileException extends RuntimeException {
    public GCPFileException(String message) {
        super(message);
    }

    public GCPFileException(String message, Throwable cause) {
        super(message, cause);
    }
}