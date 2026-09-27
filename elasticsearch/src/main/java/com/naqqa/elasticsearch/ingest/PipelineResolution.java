package com.naqqa.elasticsearch.ingest;

import java.util.ArrayList;
import java.util.List;

public final class PipelineResolution {

    private PipelineResolution() {
    }

    public static final String NONE = "_none";

    public static List<String> resolve(String requestPipeline, String defaultPipeline, String finalPipeline) {
        List<String> result = new ArrayList<>();
        String first = isSet(requestPipeline) ? requestPipeline : defaultPipeline;
        if (isSet(first) && !NONE.equals(first)) {
            result.add(first);
        }
        if (isSet(finalPipeline) && !NONE.equals(finalPipeline)) {
            result.add(finalPipeline);
        }
        return result;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }
}
