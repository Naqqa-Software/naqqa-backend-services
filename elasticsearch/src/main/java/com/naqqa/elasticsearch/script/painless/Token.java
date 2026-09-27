package com.naqqa.elasticsearch.script.painless;

public record Token(TokenType type, String text, Object literal, int pos, int line) {

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}
