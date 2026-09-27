package com.naqqa.elasticsearch.index.mapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TypeParsers {

    private TypeParsers() {
    }

    public static Map<String, TypeParser> builtin() {
        Map<String, TypeParser> m = new LinkedHashMap<>();
        m.put("object", ObjectMapper::parse);
        m.put("nested", ObjectMapper::parse);
        m.put("text", TextFieldMapper::parse);
        m.put("keyword", KeywordFieldMapper::parse);
        m.put("long", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.LONG));
        m.put("integer", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.INTEGER));
        m.put("short", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.SHORT));
        m.put("byte", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.BYTE));
        m.put("double", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.DOUBLE));
        m.put("float", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.FLOAT));
        m.put("half_float", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.HALF_FLOAT));
        m.put("scaled_float", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.SCALED_FLOAT));
        m.put("unsigned_long", NumberFieldMapper.typeParser(NumberFieldMapper.NumberType.UNSIGNED_LONG));
        m.put("boolean", BooleanFieldMapper::parse);
        m.put("date", DateFieldMapper.typeParser(false));
        m.put("date_nanos", DateFieldMapper.typeParser(true));
        m.put("binary", BinaryFieldMapper::parse);
        m.put("flattened", FlattenedFieldMapper::parse);
        m.put("join", JoinFieldMapper::parse);
        m.put("integer_range", RangeFieldMapper.typeParser(RangeFieldMapper.RangeType.INTEGER));
        m.put("long_range", RangeFieldMapper.typeParser(RangeFieldMapper.RangeType.LONG));
        m.put("float_range", RangeFieldMapper.typeParser(RangeFieldMapper.RangeType.FLOAT));
        m.put("double_range", RangeFieldMapper.typeParser(RangeFieldMapper.RangeType.DOUBLE));
        m.put("date_range", RangeFieldMapper.typeParser(RangeFieldMapper.RangeType.DATE));
        m.put("ip_range", RangeFieldMapper.typeParser(RangeFieldMapper.RangeType.IP));
        m.put("ip", IpFieldMapper::parse);
        m.put("geo_point", GeoPointFieldMapper::parse);
        m.put("geo_shape", GeoShapeFieldMapper::parse);
        m.put("point", PointFieldMapper::parse);
        m.put("shape", ShapeFieldMapper::parse);
        m.put("completion", CompletionFieldMapper::parse);
        m.put("search_as_you_type", SearchAsYouTypeFieldMapper::parse);
        m.put("token_count", TokenCountFieldMapper::parse);
        m.put("alias", FieldAliasMapper::parse);
        m.put("wildcard", WildcardFieldMapper::parse);
        m.put("constant_keyword", ConstantKeywordFieldMapper::parse);
        m.put("version", VersionStringFieldMapper::parse);
        m.put("histogram", HistogramFieldMapper::parse);
        m.put("rank_feature", RankFeatureFieldMapper::parse);
        m.put("rank_features", RankFeaturesFieldMapper::parse);
        m.put("percolator", PercolatorFieldMapper::parse);
        m.put("dense_vector", DenseVectorFieldMapper::parse);
        m.put("sparse_vector", SparseVectorFieldMapper::parse);
        return m;
    }
}
