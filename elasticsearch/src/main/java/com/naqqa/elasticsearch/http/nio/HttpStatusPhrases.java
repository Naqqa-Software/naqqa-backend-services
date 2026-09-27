package com.naqqa.elasticsearch.http.nio;

import java.util.Map;

final class HttpStatusPhrases {

    private static final Map<Integer, String> PHRASES = Map.ofEntries(
        Map.entry(200, "OK"),
        Map.entry(201, "Created"),
        Map.entry(202, "Accepted"),
        Map.entry(204, "No Content"),
        Map.entry(206, "Partial Content"),
        Map.entry(301, "Moved Permanently"),
        Map.entry(304, "Not Modified"),
        Map.entry(400, "Bad Request"),
        Map.entry(401, "Unauthorized"),
        Map.entry(403, "Forbidden"),
        Map.entry(404, "Not Found"),
        Map.entry(405, "Method Not Allowed"),
        Map.entry(409, "Conflict"),
        Map.entry(413, "Payload Too Large"),
        Map.entry(414, "URI Too Long"),
        Map.entry(429, "Too Many Requests"),
        Map.entry(500, "Internal Server Error"),
        Map.entry(501, "Not Implemented"),
        Map.entry(503, "Service Unavailable")
    );

    private HttpStatusPhrases() {
    }

    static String forStatus(int status) {
        return PHRASES.getOrDefault(status, "Unknown");
    }
}
