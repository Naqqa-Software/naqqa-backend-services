package com.naqqa.elasticsearch.indices.datastream;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DataStream {

    private final String name;
    private final List<String> backingIndices;
    private final long generation;
    private final String timestampField;

    public DataStream(String name, List<String> backingIndices, long generation, String timestampField) {
        this.name = name;
        this.backingIndices = List.copyOf(backingIndices);
        this.generation = generation;
        this.timestampField = timestampField == null ? "@timestamp" : timestampField;
    }

    public String getName() {
        return name;
    }

    public List<String> getBackingIndices() {
        return backingIndices;
    }

    public long getGeneration() {
        return generation;
    }

    public String getTimestampField() {
        return timestampField;
    }

    public String getWriteIndex() {
        return backingIndices.isEmpty() ? null : backingIndices.get(backingIndices.size() - 1);
    }

    public DataStream rollover(String newIndexName) {
        List<String> updated = new ArrayList<>(backingIndices);
        updated.add(newIndexName);
        return new DataStream(name, updated, generation + 1, timestampField);
    }

    public static String backingIndexName(String dataStreamName, long generation) {
        return String.format(".ds-%s-%06d", dataStreamName, generation);
    }

    @SuppressWarnings("unchecked")
    public static void validateTimestampMapping(String timestampField, Map<String, Object> mappings) {
        Object properties = mappings.get("properties");
        if (!(properties instanceof Map)) {
            throw new ElasticsearchException(
                "data stream mapping is missing a [{}] field of type [date]", timestampField);
        }
        Object field = ((Map<String, Object>) properties).get(timestampField);
        if (!(field instanceof Map)) {
            throw new ElasticsearchException(
                "data stream mapping is missing a [{}] field of type [date]", timestampField);
        }
        Object type = ((Map<String, Object>) field).get("type");
        if (!"date".equals(type) && !"date_nanos".equals(type)) {
            throw new ElasticsearchException(
                "data stream timestamp field [{}] must be mapped as type [date] or [date_nanos], got [{}]",
                timestampField, type);
        }
    }
}
