package com.naqqa.elasticsearch.http;

public enum XContentType {
    JSON("application/json"),
    SMILE("application/smile"),
    YAML("application/yaml"),
    CBOR("application/cbor");

    private final String mediaType;

    XContentType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }
}
