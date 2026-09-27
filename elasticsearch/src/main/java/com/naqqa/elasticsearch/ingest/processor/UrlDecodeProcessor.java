package com.naqqa.elasticsearch.ingest.processor;

import com.naqqa.elasticsearch.ingest.Processor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class UrlDecodeProcessor {

    public static final String TYPE = "urldecode";

    private UrlDecodeProcessor() {
    }

    public static Processor.Factory factory() {
        return StringTransformProcessor.factory(TYPE, s -> URLDecoder.decode(s, StandardCharsets.UTF_8));
    }
}
