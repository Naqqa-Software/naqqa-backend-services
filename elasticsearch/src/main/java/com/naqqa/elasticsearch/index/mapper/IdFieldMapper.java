package com.naqqa.elasticsearch.index.mapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class IdFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_id";

    public IdFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_id";
    }

    public void createField(ParseContext context, String id) {
        context.addIndexableField(IndexableField.indexedText(NAME, List.of(new IndexedTerm(id, 0, 0, id.length())), false));
        context.addIndexableField(IndexableField.stored(NAME, id.getBytes(StandardCharsets.UTF_8)));
    }
}
