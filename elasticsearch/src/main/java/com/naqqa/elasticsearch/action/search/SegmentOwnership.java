package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReaderSource;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class SegmentOwnership {

    private static final Map<SegmentReader, String> OWNERS = Collections.synchronizedMap(new WeakHashMap<>());

    private SegmentOwnership() {
    }

    public static void register(SegmentReader segment, String index) {
        if (segment != null && index != null) {
            OWNERS.put(segment, index);
        }
    }

    public static void register(EngineSearcher searcher, String index) {
        if (searcher == null) {
            return;
        }
        for (SegmentReader segment : searcher.leaves()) {
            register(segment, index);
        }
    }

    public static String indexOf(SegmentReader segment) {
        return segment == null ? null : OWNERS.get(segment);
    }

    public static SegmentReader segmentOf(LeafReader reader) {
        if (reader instanceof SegmentReaderSource source) {
            return source.segmentReader();
        }
        return null;
    }

    public static String indexOf(LeafReader reader) {
        return indexOf(segmentOf(reader));
    }
}
