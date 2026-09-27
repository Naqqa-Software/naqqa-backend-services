package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

public final class RankFeatureFieldMapper extends FieldMapper {

    public static final String TYPE = "rank_feature";

    private final boolean positiveScoreImpact;

    private RankFeatureFieldMapper(String simpleName, String fullPath, JsonObject node, boolean positiveScoreImpact) {
        super(simpleName, fullPath, node);
        this.positiveScoreImpact = positiveScoreImpact;
    }

    public static Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext ctx, int depth) {
        boolean positiveScoreImpact = getBool(node, "positive_score_impact", true);
        return new RankFeatureFieldMapper(name, fullPath, node, positiveScoreImpact);
    }

    @Override
    public String typeName() {
        return TYPE;
    }

    public boolean positiveScoreImpact() {
        return positiveScoreImpact;
    }

    @Override
    protected void parseCreateField(ParseContext context, Object value) {
        double v = value instanceof Number n ? n.doubleValue() : Double.parseDouble(stringValue(value));
        if (v < 0) {
            throw new IllegalArgumentException("rank_feature field [" + fullPath + "] must be a positive value");
        }
        context.addIndexableField(IndexableField.numericDocValue(fullPath, NumericUtils.floatToSortableInt((float) v)));
    }
}
