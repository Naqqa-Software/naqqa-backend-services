package com.naqqa.elasticsearch.index.mapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class FieldNamesFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_field_names";

    private final boolean enabled;

    public FieldNamesFieldMapper(boolean enabled) {
        super(NAME);
        this.enabled = enabled;
    }

    @Override
    public String typeName() {
        return "_field_names";
    }

    public boolean enabled() {
        return enabled;
    }

    public void createField(ParseContext context, Set<String> fieldNames) {
        if (!enabled || fieldNames.isEmpty()) {
            return;
        }
        List<byte[]> values = new ArrayList<>();
        for (String name : fieldNames) {
            values.add(name.getBytes(StandardCharsets.UTF_8));
        }
        context.addIndexableField(IndexableField.sortedSetDocValues(NAME, values));
    }
}

final class IgnoredFieldMapper extends MetadataFieldMapper {

    static final String NAME = "_ignored";

    IgnoredFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_ignored";
    }

    void createField(ParseContext context, Set<String> ignored) {
        if (ignored.isEmpty()) {
            return;
        }
        List<byte[]> values = new ArrayList<>();
        for (String name : ignored) {
            values.add(name.getBytes(StandardCharsets.UTF_8));
        }
        context.addIndexableField(IndexableField.sortedSetDocValues(NAME, values));
    }
}
