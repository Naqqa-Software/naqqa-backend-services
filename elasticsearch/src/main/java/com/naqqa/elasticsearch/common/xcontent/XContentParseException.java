package com.naqqa.elasticsearch.common.xcontent;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.common.exception.RestStatus;

public class XContentParseException extends ElasticsearchException {

    private final XContentLocation location;

    public XContentParseException(String message) {
        this(XContentLocation.UNKNOWN, message, null);
    }

    public XContentParseException(XContentLocation location, String message) {
        this(location, message, null);
    }

    public XContentParseException(XContentLocation location, String message, Throwable cause) {
        super(message, cause);
        this.location = location == null ? XContentLocation.UNKNOWN : location;
    }

    public XContentLocation getLocation() {
        return location;
    }

    public int getLineNumber() {
        return location.lineNumber();
    }

    public int getColumnNumber() {
        return location.columnNumber();
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        if (location.isKnown()) {
            return "[" + location + "] " + message;
        }
        return message;
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }
}
