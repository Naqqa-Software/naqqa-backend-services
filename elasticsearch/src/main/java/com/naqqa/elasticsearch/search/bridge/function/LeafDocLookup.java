package com.naqqa.elasticsearch.search.bridge.function;

import com.naqqa.elasticsearch.codec.fieldinfos.DocValuesType;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class LeafDocLookup implements DocLookup {

    private final LeafReaderContext context;
    private int docId;

    public LeafDocLookup(LeafReaderContext context) {
        this.context = context;
    }

    public void setDocId(int docId) {
        this.docId = docId;
    }

    @Override
    public int docId() {
        return docId;
    }

    @Override
    public boolean containsKey(String field) {
        return context.reader().fieldInfo(field) != null;
    }

    @Override
    public ScriptDocValues<?> get(String field) {
        try {
            FieldInfo info = context.reader().fieldInfo(field);
            if (info == null || info.docValuesType() == DocValuesType.NONE) {
                throw new IllegalArgumentException("No field found for [" + field + "] in mapping");
            }
            return switch (info.docValuesType()) {
                case NUMERIC -> {
                    var dv = context.reader().numericDocValues(field);
                    yield dv != null && dv.advanceExact(docId)
                        ? ScriptDocValues.longs(dv.longValue())
                        : ScriptDocValues.longs();
                }
                case SORTED -> {
                    var dv = context.reader().sortedDocValues(field);
                    if (dv != null && dv.advanceExact(docId)) {
                        yield ScriptDocValues.strings(new String(dv.lookupOrd(dv.ordValue()), StandardCharsets.UTF_8));
                    }
                    yield ScriptDocValues.strings();
                }
                case SORTED_SET -> {
                    var dv = context.reader().sortedSetDocValues(field);
                    if (dv != null && dv.advanceExact(docId)) {
                        List<String> values = new ArrayList<>();
                        int count = dv.docValueCount();
                        for (int i = 0; i < count; i++) {
                            values.add(new String(dv.lookupOrd((int) dv.nextOrd()), StandardCharsets.UTF_8));
                        }
                        yield ScriptDocValues.strings(values.toArray(new String[0]));
                    }
                    yield ScriptDocValues.strings();
                }
                case SORTED_NUMERIC -> {
                    var dv = context.reader().sortedNumericDocValues(field);
                    if (dv != null && dv.advanceExact(docId)) {
                        int count = dv.docValueCount();
                        long[] values = new long[count];
                        for (int i = 0; i < count; i++) {
                            values[i] = dv.nextValue();
                        }
                        yield ScriptDocValues.longs(values);
                    }
                    yield ScriptDocValues.longs();
                }
                case BINARY -> throw new IllegalArgumentException("field [" + field + "] does not support script value access");
                default -> throw new IllegalArgumentException("No field found for [" + field + "] in mapping");
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Double numericValue(String field) {
        try {
            FieldInfo info = context.reader().fieldInfo(field);
            if (info == null) {
                return null;
            }
            if (info.docValuesType() == DocValuesType.NUMERIC) {
                var dv = context.reader().numericDocValues(field);
                if (dv != null && dv.advanceExact(docId)) {
                    return (double) dv.longValue();
                }
            } else if (info.docValuesType() == DocValuesType.SORTED_NUMERIC) {
                var dv = context.reader().sortedNumericDocValues(field);
                if (dv != null && dv.advanceExact(docId) && dv.docValueCount() > 0) {
                    return (double) dv.nextValue();
                }
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
