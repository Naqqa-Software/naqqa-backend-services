package com.naqqa.elasticsearch.index.mapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class IndexFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_index";

    public IndexFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_index";
    }

    public void createField(ParseContext context, String indexName) {
        context.addIndexableField(IndexableField.indexedText(NAME, List.of(new IndexedTerm(indexName, 0, 0, indexName.length())), false));
        context.addIndexableField(IndexableField.sortedSetDocValues(NAME, List.of(indexName.getBytes(StandardCharsets.UTF_8))));
    }
}
