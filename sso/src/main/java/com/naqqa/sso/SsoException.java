package com.naqqa.sso;

public class SsoException extends RuntimeException {
    private final String code;

    public SsoException(String code) {
        super(code);
        this.code = code;
    }

    public SsoException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
