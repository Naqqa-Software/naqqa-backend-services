package com.naqqa.elasticsearch.index.mapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RoutingFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_routing";

    private final boolean required;

    public RoutingFieldMapper(boolean required) {
        super(NAME);
        this.required = required;
    }

    @Override
    public String typeName() {
        return "_routing";
    }

    public boolean required() {
        return required;
    }

    public void createField(ParseContext context, String routing) {
        if (routing == null) {
            if (required) {
                throw new IllegalArgumentException("routing is required for this type");
            }
            return;
        }
        context.addIndexableField(IndexableField.indexedText(NAME, List.of(new IndexedTerm(routing, 0, 0, routing.length())), false));
        context.addIndexableField(IndexableField.stored(NAME, routing.getBytes(StandardCharsets.UTF_8)));
    }
}
