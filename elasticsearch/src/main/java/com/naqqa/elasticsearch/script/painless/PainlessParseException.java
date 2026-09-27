package com.naqqa.elasticsearch.script.painless;

public class PainlessParseException extends RuntimeException {

    private final int pos;

    public PainlessParseException(String message, int pos) {
        super(message);
        this.pos = pos;
    }

    public int pos() {
        return pos;
    }
}
