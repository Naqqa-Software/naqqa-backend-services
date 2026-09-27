package com.naqqa.elasticsearch.script.painless;

public class PainlessExplainException extends RuntimeException {

    private final Object value;

    public PainlessExplainException(Object value) {
        super("Explain: " + value);
        this.value = value;
    }

    public Object value() {
        return value;
    }
}
