package com.naqqa.elasticsearch.common.json;

import com.naqqa.elasticsearch.common.xcontent.XContentLocation;
import com.naqqa.elasticsearch.common.xcontent.XContentParseException;

public class JsonParseException extends XContentParseException {

    public JsonParseException(XContentLocation location, String message) {
        super(location, message);
    }

    public JsonParseException(XContentLocation location, String message, Throwable cause) {
        super(location, message, cause);
    }
}
