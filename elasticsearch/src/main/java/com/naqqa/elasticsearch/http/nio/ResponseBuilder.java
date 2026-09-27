package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.CompressionUtil;
import com.naqqa.elasticsearch.http.CorsHandler;
import com.naqqa.elasticsearch.http.HttpServerConfig;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.RestResponse;

import java.io.IOException;
import java.util.Locale;

final class ResponseBuilder {

    record Encoded(byte[] bytes, boolean close) {
    }

    private ResponseBuilder() {
    }

    static Encoded encode(RestRequest request, RestResponse response, String httpVersion, HttpServerConfig config, CorsHandler corsHandler) {
        corsHandler.applyToResponse(request, response);
        byte[] body = response.content();
        boolean isHead = request.method() == RestMethod.HEAD;
        CompressionUtil.Encoding encoding = CompressionUtil.negotiateResponseEncoding(request.header("Accept-Encoding"));
        byte[] finalBody = body;
        String encodingToken = null;
        if (encoding != CompressionUtil.Encoding.IDENTITY && body.length > 0) {
            try {
                byte[] compressed = CompressionUtil.compress(body, encoding);
                if (compressed.length < body.length) {
                    finalBody = compressed;
                    encodingToken = CompressionUtil.encodingToken(encoding);
                }
            } catch (IOException ignored) {
            }
        }
        if (response.contentType() != null) {
            response.headers().set("Content-Type", response.contentType());
        }
        if (encodingToken != null) {
            response.headers().set("Content-Encoding", encodingToken);
        }
        response.headers().set("Content-Length", String.valueOf(finalBody.length));
        boolean keepAlive = determineKeepAlive(request, httpVersion);
        response.headers().set("Connection", keepAlive ? "keep-alive" : "close");
        byte[] bytes = HttpResponseEncoder.encode(response.status(), response.headers(), finalBody, isHead);
        return new Encoded(bytes, !keepAlive);
    }

    static boolean determineKeepAlive(RestRequest request, String httpVersion) {
        String connection = request.header("Connection");
        if (connection != null) {
            String lower = connection.toLowerCase(Locale.ROOT);
            if (lower.contains("close")) {
                return false;
            }
            if (lower.contains("keep-alive")) {
                return true;
            }
        }
        return httpVersion == null || !httpVersion.equals("HTTP/1.0");
    }
}
