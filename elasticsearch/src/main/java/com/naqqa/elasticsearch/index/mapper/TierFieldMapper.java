package com.naqqa.elasticsearch.index.mapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class TierFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_tier";

    public TierFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_tier";
    }

    public void createField(ParseContext context, String tier) {
        context.addIndexableField(IndexableField.indexedText(NAME, List.of(new IndexedTerm(tier, 0, 0, tier.length())), false));
        context.addIndexableField(IndexableField.sortedSetDocValues(NAME, List.of(tier.getBytes(StandardCharsets.UTF_8))));
    }
}
