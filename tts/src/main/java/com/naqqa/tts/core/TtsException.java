package com.naqqa.tts.core;

public class TtsException extends RuntimeException {

    public enum Reason {
        INVALID,
        UNSUPPORTED,
        RATE_LIMITED,
        BUSY,
        UNAVAILABLE,
        FAILED
    }

    private final Reason reason;

    public TtsException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TtsException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
