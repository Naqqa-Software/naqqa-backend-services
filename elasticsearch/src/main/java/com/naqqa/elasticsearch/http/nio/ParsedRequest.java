package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.HttpHeaders;

public final class ParsedRequest {

    public final String method;
    public final String rawTarget;
    public final String version;
    public final HttpHeaders headers;
    public final byte[] body;
    public final int consumedLength;

    public ParsedRequest(String method, String rawTarget, String version, HttpHeaders headers, byte[] body, int consumedLength) {
        this.method = method;
        this.rawTarget = rawTarget;
        this.version = version;
        this.headers = headers;
        this.body = body;
        this.consumedLength = consumedLength;
    }
}
