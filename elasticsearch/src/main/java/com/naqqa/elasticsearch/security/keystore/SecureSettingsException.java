package com.naqqa.elasticsearch.security.keystore;

public class SecureSettingsException extends RuntimeException {

    public SecureSettingsException(String message) {
        super(message);
    }

    public SecureSettingsException(String message, Throwable cause) {
        super(message, cause);
    }
}
