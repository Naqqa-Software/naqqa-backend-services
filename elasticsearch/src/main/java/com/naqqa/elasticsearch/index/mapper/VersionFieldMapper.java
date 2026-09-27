package com.naqqa.elasticsearch.index.mapper;

public final class VersionFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_version";

    public VersionFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_version";
    }

    public void createField(ParseContext context, long version) {
        context.addIndexableField(IndexableField.numericDocValue(NAME, version));
    }
}

final class SeqNoFieldMapper extends MetadataFieldMapper {

    static final String NAME = "_seq_no";

    SeqNoFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_seq_no";
    }

    void createField(ParseContext context, long seqNo) {
        context.addIndexableField(IndexableField.numericDocValue(NAME, seqNo));
        context.addIndexableField(IndexableField.point(NAME, new byte[][] {NumericUtils.longToSortableBytes(seqNo)}));
    }
}

final class PrimaryTermFieldMapper extends MetadataFieldMapper {

    static final String NAME = "_primary_term";

    PrimaryTermFieldMapper() {
        super(NAME);
    }

    @Override
    public String typeName() {
        return "_primary_term";
    }

    void createField(ParseContext context, long primaryTerm) {
        context.addIndexableField(IndexableField.numericDocValue(NAME, primaryTerm));
    }
}
