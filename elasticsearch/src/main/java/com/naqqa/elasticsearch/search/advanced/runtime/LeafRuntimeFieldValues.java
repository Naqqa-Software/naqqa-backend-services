package com.naqqa.elasticsearch.search.advanced.runtime;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.index.mapper.RuntimeFieldScript;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LeafRuntimeFieldValues implements RuntimeFieldValueSource {

    private final LeafReader reader;
    private final RuntimeFieldScript script;
    private final List<String> dependentFields;

    public LeafRuntimeFieldValues(LeafReader reader, RuntimeFieldScript script, Collection<String> dependentFields) {
        this.reader = reader;
        this.script = script;
        this.dependentFields = List.copyOf(dependentFields);
    }

    @Override
    public List<Object> values(int doc) throws IOException {
        Map<String, Object> sourceAsMap = new LinkedHashMap<>();
        for (String field : dependentFields) {
            Object value = readFieldValue(field, doc);
            if (value != null) {
                sourceAsMap.put(field, value);
            }
        }
        List<Object> result = script.execute(sourceAsMap);
        return result == null ? List.of() : result;
    }

    private Object readFieldValue(String field, int doc) throws IOException {
        NumericDocValuesReader numeric = reader.numericDocValues(field);
        if (numeric != null) {
            return numeric.advanceExact(doc) ? numeric.longValue() : null;
        }
        SortedDocValuesReader sorted = reader.sortedDocValues(field);
        if (sorted != null) {
            if (!sorted.advanceExact(doc)) {
                return null;
            }
            byte[] bytes = sorted.lookupOrd(sorted.ordValue());
            return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
        SortedSetDocValuesReader sortedSet = reader.sortedSetDocValues(field);
        if (sortedSet != null) {
            return readSortedSet(sortedSet, doc);
        }
        return null;
    }

    private Object readSortedSet(SortedSetDocValuesReader sortedSet, int doc) {
        if (!sortedSet.advanceExact(doc)) {
            return null;
        }
        int count = sortedSet.docValueCount();
        if (count == 0) {
            return null;
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] bytes = sortedSet.lookupOrd((int) sortedSet.nextOrd());
            values.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return count == 1 ? values.get(0) : values;
    }
}
