package com.naqqa.elasticsearch.codec.segment;

import java.util.Map;
import java.util.Set;

public final class SegmentInfo {

    private final String name;
    private final byte[] id;
    private final int maxDoc;
    private final String codecName;
    private final Set<String> files;
    private final Map<String, String> diagnostics;
    private final Map<String, String> attributes;
    private final String indexSort;

    public SegmentInfo(String name, byte[] id, int maxDoc, String codecName, Set<String> files,
                        Map<String, String> diagnostics, Map<String, String> attributes, String indexSort) {
        this.name = name;
        this.id = id;
        this.maxDoc = maxDoc;
        this.codecName = codecName;
        this.files = files;
        this.diagnostics = diagnostics;
        this.attributes = attributes;
        this.indexSort = indexSort;
    }

    public String name() {
        return name;
    }

    public byte[] id() {
        return id;
    }

    public int maxDoc() {
        return maxDoc;
    }

    public String codecName() {
        return codecName;
    }

    public Set<String> files() {
        return files;
    }

    public Map<String, String> diagnostics() {
        return diagnostics;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public String indexSort() {
        return indexSort;
    }
}
