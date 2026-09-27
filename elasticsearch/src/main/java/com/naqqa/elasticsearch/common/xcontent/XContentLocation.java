package com.naqqa.elasticsearch.common.xcontent;

public record XContentLocation(int lineNumber, int columnNumber) {

    public static final XContentLocation UNKNOWN = new XContentLocation(-1, -1);

    public boolean isKnown() {
        return lineNumber >= 0 && columnNumber >= 0;
    }

    @Override
    public String toString() {
        return lineNumber + ":" + columnNumber;
    }
}
