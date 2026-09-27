package com.naqqa.elasticsearch.http;

import java.nio.charset.StandardCharsets;

public final class RestResponse {

    private final int status;
    private final String contentType;
    private final byte[] content;
    private final HttpHeaders headers = new HttpHeaders();

    public RestResponse(int status, String contentType, byte[] content) {
        this.status = status;
        this.contentType = contentType;
        this.content = content == null ? new byte[0] : content;
    }

    public static RestResponse json(int status, String jsonBody) {
        return new RestResponse(status, "application/json; charset=UTF-8", jsonBody.getBytes(StandardCharsets.UTF_8));
    }

    public static RestResponse text(int status, String body) {
        return new RestResponse(status, "text/plain; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    public static RestResponse empty(int status) {
        return new RestResponse(status, null, new byte[0]);
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] content() {
        return content;
    }

    public HttpHeaders headers() {
        return headers;
    }

    public RestResponse addHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }
}
