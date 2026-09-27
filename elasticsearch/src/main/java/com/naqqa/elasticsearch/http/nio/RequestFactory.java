package com.naqqa.elasticsearch.http.nio;

import com.naqqa.elasticsearch.http.CompressionUtil;
import com.naqqa.elasticsearch.http.HttpHeaders;
import com.naqqa.elasticsearch.http.QueryStringParser;
import com.naqqa.elasticsearch.http.RestMethod;
import com.naqqa.elasticsearch.http.RestRequest;
import com.naqqa.elasticsearch.http.UrlCodec;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RequestFactory {

    private RequestFactory() {
    }

    static RestRequest build(String method, String rawTarget, HttpHeaders headers, byte[] rawBody) {
        int q = rawTarget.indexOf('?');
        String rawPath = q >= 0 ? rawTarget.substring(0, q) : rawTarget;
        String rawQuery = q >= 0 ? rawTarget.substring(q + 1) : null;
        String path = decodePath(rawPath);
        Map<String, List<String>> queryParams = QueryStringParser.parse(rawQuery);

        byte[] body = rawBody;
        String contentEncoding = headers.getFirst("Content-Encoding");
        if (contentEncoding != null) {
            String lower = contentEncoding.toLowerCase(Locale.ROOT);
            try {
                if (lower.contains("gzip")) {
                    body = CompressionUtil.gunzip(body);
                } else if (lower.contains("deflate")) {
                    body = CompressionUtil.inflate(body);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to decompress request body", e);
            }
        }
        return new RestRequest(RestMethod.parse(method), path, rawTarget, queryParams, headers, body);
    }

    private static String decodePath(String rawPath) {
        StringBuilder sb = new StringBuilder();
        String[] segments = rawPath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(UrlCodec.decode(segments[i], false));
        }
        return sb.toString();
    }
}
