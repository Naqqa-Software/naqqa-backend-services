package com.naqqa.elasticsearch.script.painless;

public class PainlessRuntimeError extends RuntimeException {

    public PainlessRuntimeError(String message) {
        super(message);
    }

    public PainlessRuntimeError(String message, Throwable cause) {
        super(message, cause);
    }
}
