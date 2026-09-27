package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.index.query.span.FieldMaskingSpanQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanContainingQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanFirstQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanMultiTermQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanNearQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanNotQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanOrQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanTermQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanWithinQueryBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class QueryParser {

    private QueryParser() {
    }

    private static final Map<String, Function<Map<String, Object>, QueryBuilder>> PARSERS = new LinkedHashMap<>();

    private static void register(String name, Function<Map<String, Object>, QueryBuilder> fn) {
        PARSERS.put(name, fn);
    }

    static {
        register(MatchQueryBuilder.NAME, MatchQueryBuilder::fromMap);
        register(MatchPhraseQueryBuilder.NAME, MatchPhraseQueryBuilder::fromMap);
        register(MatchPhrasePrefixQueryBuilder.NAME, MatchPhrasePrefixQueryBuilder::fromMap);
        register(MatchBoolPrefixQueryBuilder.NAME, MatchBoolPrefixQueryBuilder::fromMap);
        register(MultiMatchQueryBuilder.NAME, MultiMatchQueryBuilder::fromMap);
        register(CombinedFieldsQueryBuilder.NAME, CombinedFieldsQueryBuilder::fromMap);
        register(QueryStringQueryBuilder.NAME, QueryStringQueryBuilder::fromMap);
        register(SimpleQueryStringQueryBuilder.NAME, SimpleQueryStringQueryBuilder::fromMap);
        register(IntervalsQueryBuilder.NAME, IntervalsQueryBuilder::fromMap);

        register(TermQueryBuilder.NAME, TermQueryBuilder::fromMap);
        register(TermsQueryBuilder.NAME, TermsQueryBuilder::fromMap);
        register(TermsSetQueryBuilder.NAME, TermsSetQueryBuilder::fromMap);
        register(RangeQueryBuilder.NAME, RangeQueryBuilder::fromMap);
        register(ExistsQueryBuilder.NAME, ExistsQueryBuilder::fromMap);
        register(PrefixQueryBuilder.NAME, PrefixQueryBuilder::fromMap);
        register(WildcardQueryBuilder.NAME, WildcardQueryBuilder::fromMap);
        register(RegexpQueryBuilder.NAME, RegexpQueryBuilder::fromMap);
        register(FuzzyQueryBuilder.NAME, FuzzyQueryBuilder::fromMap);
        register(IdsQueryBuilder.NAME, IdsQueryBuilder::fromMap);

        register(BoolQueryBuilder.NAME, BoolQueryBuilder::fromMap);
        register(BoostingQueryBuilder.NAME, BoostingQueryBuilder::fromMap);
        register(ConstantScoreQueryBuilder.NAME, ConstantScoreQueryBuilder::fromMap);
        register(DisMaxQueryBuilder.NAME, DisMaxQueryBuilder::fromMap);
        register(FunctionScoreQueryBuilder.NAME, FunctionScoreQueryBuilder::fromMap);
        register(ScriptScoreQueryBuilder.NAME, ScriptScoreQueryBuilder::fromMap);
        register(ScriptQueryBuilder.NAME, ScriptQueryBuilder::fromMap);
        register(MatchAllQueryBuilder.NAME, MatchAllQueryBuilder::fromMap);
        register(MatchNoneQueryBuilder.NAME, MatchNoneQueryBuilder::fromMap);

        register(NestedQueryBuilder.NAME, NestedQueryBuilder::fromMap);
        register(HasChildQueryBuilder.NAME, HasChildQueryBuilder::fromMap);
        register(HasParentQueryBuilder.NAME, HasParentQueryBuilder::fromMap);
        register(ParentIdQueryBuilder.NAME, ParentIdQueryBuilder::fromMap);

        register(GeoBoundingBoxQueryBuilder.NAME, GeoBoundingBoxQueryBuilder::fromMap);
        register(GeoDistanceQueryBuilder.NAME, GeoDistanceQueryBuilder::fromMap);
        register(GeoPolygonQueryBuilder.NAME, GeoPolygonQueryBuilder::fromMap);
        register(GeoShapeQueryBuilder.NAME, GeoShapeQueryBuilder::fromMap);
        register(ShapeQueryBuilder.NAME, ShapeQueryBuilder::fromMap);

        register(MoreLikeThisQueryBuilder.NAME, MoreLikeThisQueryBuilder::fromMap);
        register(PercolateQueryBuilder.NAME, PercolateQueryBuilder::fromMap);
        register(RankFeatureQueryBuilder.NAME, RankFeatureQueryBuilder::fromMap);
        register(DistanceFeatureQueryBuilder.NAME, DistanceFeatureQueryBuilder::fromMap);
        register(PinnedQueryBuilder.NAME, PinnedQueryBuilder::fromMap);
        register(WrapperQueryBuilder.NAME, WrapperQueryBuilder::fromMap);
        register(KnnQueryBuilder.NAME, KnnQueryBuilder::fromMap);

        register(SpanTermQueryBuilder.NAME, SpanTermQueryBuilder::fromMap);
        register(SpanNearQueryBuilder.NAME, SpanNearQueryBuilder::fromMap);
        register(SpanOrQueryBuilder.NAME, SpanOrQueryBuilder::fromMap);
        register(SpanNotQueryBuilder.NAME, SpanNotQueryBuilder::fromMap);
        register(SpanFirstQueryBuilder.NAME, SpanFirstQueryBuilder::fromMap);
        register(SpanContainingQueryBuilder.NAME, SpanContainingQueryBuilder::fromMap);
        register(SpanWithinQueryBuilder.NAME, SpanWithinQueryBuilder::fromMap);
        register(SpanMultiTermQueryBuilder.NAME, SpanMultiTermQueryBuilder::fromMap);
        register(FieldMaskingSpanQueryBuilder.NAME, FieldMaskingSpanQueryBuilder::fromMap);
    }

    public static QueryBuilder parseQuery(Map<String, Object> json) {
        if (json == null || json.isEmpty()) {
            throw QueryParseUtils.error("query malformed, no field after start_object");
        }
        if (json.size() > 1) {
            List<String> keys = new ArrayList<>(json.keySet());
            throw QueryParseUtils.error("[{}] malformed query, expected [END_OBJECT] but found [FIELD_NAME] [{}]", keys.get(0), keys.get(1));
        }
        Map.Entry<String, Object> entry = json.entrySet().iterator().next();
        String name = entry.getKey();
        Function<Map<String, Object>, QueryBuilder> parser = PARSERS.get(name);
        if (parser == null) {
            throw QueryParseUtils.unknownQueryType(name, PARSERS.keySet());
        }
        Map<String, Object> value = entry.getValue() instanceof Map<?, ?> ? QueryParseUtils.asMap(entry.getValue(), name) : new LinkedHashMap<>();
        return parser.apply(value);
    }

    public static java.util.Set<String> registeredNames() {
        return PARSERS.keySet();
    }
}
