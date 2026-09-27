package com.naqqa.elasticsearch.codec.fieldinfos;

import java.util.Map;

public final class FieldInfo {

    private final String name;
    private final int number;
    private final boolean indexed;
    private final int indexOptions;
    private final boolean hasNorms;
    private final boolean hasVectors;
    private final boolean hasPayloads;
    private final DocValuesType docValuesType;
    private final int pointDimensionCount;
    private final int pointNumBytes;
    private final Map<String, String> attributes;

    public FieldInfo(String name, int number, boolean indexed, int indexOptions, boolean hasNorms, boolean hasVectors,
                      boolean hasPayloads, DocValuesType docValuesType, int pointDimensionCount, int pointNumBytes,
                      Map<String, String> attributes) {
        this.name = name;
        this.number = number;
        this.indexed = indexed;
        this.indexOptions = indexOptions;
        this.hasNorms = hasNorms;
        this.hasVectors = hasVectors;
        this.hasPayloads = hasPayloads;
        this.docValuesType = docValuesType;
        this.pointDimensionCount = pointDimensionCount;
        this.pointNumBytes = pointNumBytes;
        this.attributes = attributes;
    }

    public String name() {
        return name;
    }

    public int number() {
        return number;
    }

    public boolean indexed() {
        return indexed;
    }

    public int indexOptions() {
        return indexOptions;
    }

    public boolean hasNorms() {
        return hasNorms;
    }

    public boolean hasVectors() {
        return hasVectors;
    }

    public boolean hasPayloads() {
        return hasPayloads;
    }

    public DocValuesType docValuesType() {
        return docValuesType;
    }

    public int pointDimensionCount() {
        return pointDimensionCount;
    }

    public int pointNumBytes() {
        return pointNumBytes;
    }

    public Map<String, String> attributes() {
        return attributes;
    }
}
