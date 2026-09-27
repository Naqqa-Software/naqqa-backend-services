package com.naqqa.elasticsearch.http;

import java.util.List;

public final class ContentTypeNegotiator {

    private ContentTypeNegotiator() {
    }

    public static MediaType negotiateAccept(String acceptHeader, XContentType fallback) {
        List<MediaType> candidates = MediaType.parseAccept(acceptHeader);
        for (MediaType candidate : candidates) {
            if (candidate.type().equals("*") || candidate.canonical() != null) {
                return candidate;
            }
        }
        return new MediaType("application", fallback.mediaType().substring("application/".length()), java.util.Map.of(), 1.0);
    }

    public static XContentType resolve(MediaType mediaType, XContentType fallback) {
        if (mediaType == null) {
            return fallback;
        }
        XContentType canonical = mediaType.canonical();
        return canonical == null ? fallback : canonical;
    }
}
