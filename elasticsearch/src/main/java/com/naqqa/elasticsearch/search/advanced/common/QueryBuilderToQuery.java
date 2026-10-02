package com.naqqa.elasticsearch.search.advanced.common;

import com.naqqa.elasticsearch.common.automaton.RegExp;
import com.naqqa.elasticsearch.common.geo.DistanceUnit;
import com.naqqa.elasticsearch.common.geo.GeoDistance;
import com.naqqa.elasticsearch.common.geo.GeoPoint;
import com.naqqa.elasticsearch.common.geo.geometry.Geometry;
import com.naqqa.elasticsearch.common.unit.Fuzziness;
import com.naqqa.elasticsearch.index.query.AbstractQueryBuilder;
import com.naqqa.elasticsearch.index.query.BoolQueryBuilder;
import com.naqqa.elasticsearch.index.query.BoostingQueryBuilder;
import com.naqqa.elasticsearch.index.query.CombinedFieldsQueryBuilder;
import com.naqqa.elasticsearch.index.query.ConstantScoreQueryBuilder;
import com.naqqa.elasticsearch.index.query.DecayScoreFunction;
import com.naqqa.elasticsearch.index.query.DisMaxQueryBuilder;
import com.naqqa.elasticsearch.index.query.ExistsQueryBuilder;
import com.naqqa.elasticsearch.index.query.FieldValueFactorScoreFunction;
import com.naqqa.elasticsearch.index.query.FunctionScoreQueryBuilder;
import com.naqqa.elasticsearch.index.query.FuzzyQueryBuilder;
import com.naqqa.elasticsearch.index.query.GeoBoundingBoxQueryBuilder;
import com.naqqa.elasticsearch.index.query.GeoDistanceQueryBuilder;
import com.naqqa.elasticsearch.index.query.GeoPolygonQueryBuilder;
import com.naqqa.elasticsearch.index.query.GeoShapeQueryBuilder;
import com.naqqa.elasticsearch.index.query.HasChildQueryBuilder;
import com.naqqa.elasticsearch.index.query.HasParentQueryBuilder;
import com.naqqa.elasticsearch.index.query.IdsQueryBuilder;
import com.naqqa.elasticsearch.index.query.KnnQueryBuilder;
import com.naqqa.elasticsearch.index.query.MatchAllQueryBuilder;
import com.naqqa.elasticsearch.index.query.MatchBoolPrefixQueryBuilder;
import com.naqqa.elasticsearch.index.query.MatchNoneQueryBuilder;
import com.naqqa.elasticsearch.index.query.MatchPhrasePrefixQueryBuilder;
import com.naqqa.elasticsearch.index.query.MatchPhraseQueryBuilder;
import com.naqqa.elasticsearch.index.query.MatchQueryBuilder;
import com.naqqa.elasticsearch.index.query.MinimumShouldMatch;
import com.naqqa.elasticsearch.index.query.MultiMatchQueryBuilder;
import com.naqqa.elasticsearch.index.query.NestedQueryBuilder;
import com.naqqa.elasticsearch.index.query.Operator;
import com.naqqa.elasticsearch.index.query.ParentIdQueryBuilder;
import com.naqqa.elasticsearch.index.query.PinnedQueryBuilder;
import com.naqqa.elasticsearch.index.query.PrefixQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParseUtils;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.index.query.QueryStringQueryBuilder;
import com.naqqa.elasticsearch.index.query.RandomScoreFunction;
import com.naqqa.elasticsearch.index.query.RangeQueryBuilder;
import com.naqqa.elasticsearch.index.query.RegexpQueryBuilder;
import com.naqqa.elasticsearch.index.query.ScoreFunctionBuilder;
import com.naqqa.elasticsearch.index.query.ScriptQueryBuilder;
import com.naqqa.elasticsearch.index.query.ScriptScoreFunction;
import com.naqqa.elasticsearch.index.query.ScriptScoreQueryBuilder;
import com.naqqa.elasticsearch.index.query.ShapeQueryBuilder;
import com.naqqa.elasticsearch.index.query.SimpleQueryStringQueryBuilder;
import com.naqqa.elasticsearch.index.query.TermQueryBuilder;
import com.naqqa.elasticsearch.index.query.TermsQueryBuilder;
import com.naqqa.elasticsearch.index.query.WeightScoreFunction;
import com.naqqa.elasticsearch.index.query.WildcardQueryBuilder;
import com.naqqa.elasticsearch.index.query.WrapperQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.FieldMaskingSpanQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanContainingQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanFirstQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanMultiTermQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanNearQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanNotQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanOrQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanTermQueryBuilder;
import com.naqqa.elasticsearch.index.query.span.SpanWithinQueryBuilder;
import com.naqqa.elasticsearch.ingest.json.IngestJsonParser;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.search.bridge.BoostingQuery;
import com.naqqa.elasticsearch.search.bridge.FieldExistsQuery;
import com.naqqa.elasticsearch.search.bridge.PinnedQuery;
import com.naqqa.elasticsearch.search.bridge.PrefixPhraseQuery;
import com.naqqa.elasticsearch.search.bridge.ScriptFilterQuery;
import com.naqqa.elasticsearch.search.bridge.TermRangeQuery;
import com.naqqa.elasticsearch.search.bridge.function.CompositeFunctionScoreQuery;
import com.naqqa.elasticsearch.search.bridge.function.FunctionSpec;
import com.naqqa.elasticsearch.search.bridge.geo.GeoBoundingBoxQuery;
import com.naqqa.elasticsearch.search.bridge.geo.GeoDistanceQuery;
import com.naqqa.elasticsearch.search.bridge.geo.GeoPolygonQuery;
import com.naqqa.elasticsearch.search.bridge.geo.GeoShapeQuery;
import com.naqqa.elasticsearch.search.bridge.geo.GeoShapeRelations;
import com.naqqa.elasticsearch.search.bridge.join.HasChildQuery;
import com.naqqa.elasticsearch.search.bridge.join.HasParentQuery;
import com.naqqa.elasticsearch.search.bridge.join.JoinScoreMode;
import com.naqqa.elasticsearch.search.bridge.join.NestedQuery;
import com.naqqa.elasticsearch.search.bridge.join.ParentChildDocMapping;
import com.naqqa.elasticsearch.search.bridge.join.ParentIdQuery;
import com.naqqa.elasticsearch.search.bridge.span.FieldMaskingSpanQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanContainingQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanFirstQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanMultiTermQueryWrapper;
import com.naqqa.elasticsearch.search.bridge.span.SpanNearQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanNotQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanOrQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanTermQuery;
import com.naqqa.elasticsearch.search.bridge.span.SpanWithinQuery;
import com.naqqa.elasticsearch.search.query.AutomatonQuery;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.BoostQuery;
import com.naqqa.elasticsearch.search.query.ConstantScoreQuery;
import com.naqqa.elasticsearch.search.query.DisjunctionMaxQuery;
import com.naqqa.elasticsearch.search.query.MatchAllDocsQuery;
import com.naqqa.elasticsearch.search.query.MatchNoDocsQuery;
import com.naqqa.elasticsearch.search.query.PhraseQuery;
import com.naqqa.elasticsearch.search.query.PointRangeQuery;
import com.naqqa.elasticsearch.search.query.PrefixQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.vectors.query.KnnVectorQuery;
import com.naqqa.elasticsearch.search.vectors.query.VectorSegmentAccessorProvider;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class QueryBuilderToQuery {

    private static final ScriptService DEFAULT_SCRIPT_SERVICE = ScriptService.defaults();

    private QueryBuilderToQuery() {
    }

    public static final class ConversionContext {
        public static final ConversionContext EMPTY = new ConversionContext(null, null, null);

        private final VectorSegmentAccessorProvider vectorProvider;
        private final ParentChildDocMapping parentChildMapping;
        private final ScriptService scriptService;

        private ConversionContext(VectorSegmentAccessorProvider vectorProvider, ParentChildDocMapping parentChildMapping,
                                   ScriptService scriptService) {
            this.vectorProvider = vectorProvider;
            this.parentChildMapping = parentChildMapping;
            this.scriptService = scriptService;
        }

        public static ConversionContext of(VectorSegmentAccessorProvider vectorProvider, ParentChildDocMapping mapping,
                                            ScriptService scriptService) {
            return new ConversionContext(vectorProvider, mapping, scriptService);
        }

        public ConversionContext withVectorProvider(VectorSegmentAccessorProvider provider) {
            return new ConversionContext(provider, parentChildMapping, scriptService);
        }

        public ConversionContext withParentChildMapping(ParentChildDocMapping mapping) {
            return new ConversionContext(vectorProvider, mapping, scriptService);
        }

        public ConversionContext withScriptService(ScriptService service) {
            return new ConversionContext(vectorProvider, parentChildMapping, service);
        }

        VectorSegmentAccessorProvider vectorProvider() {
            return vectorProvider;
        }

        ParentChildDocMapping parentChildMapping() {
            return parentChildMapping;
        }

        ScriptService scriptService() {
            return scriptService == null ? DEFAULT_SCRIPT_SERVICE : scriptService;
        }
    }

    public static Query convert(QueryBuilder builder) {
        return convert(builder, ConversionContext.EMPTY);
    }

    public static Query convert(QueryBuilder builder, ConversionContext ctx) {
        Query query = convertInner(builder, ctx);
        if (builder instanceof AbstractQueryBuilder aqb && aqb.boost() != 1.0f) {
            return new BoostQuery(query, aqb.boost());
        }
        return query;
    }

    @SuppressWarnings("unchecked")
    private static Query convertInner(QueryBuilder builder, ConversionContext ctx) {
        if (builder instanceof MatchAllQueryBuilder) {
            return new MatchAllDocsQuery();
        }
        if (builder instanceof MatchNoneQueryBuilder) {
            return new MatchNoDocsQuery();
        }
        if (builder instanceof TermQueryBuilder t) {
            if (t.caseInsensitive()) {
                return AutomatonQuery.wildcard(t.fieldName(), escapeWildcardLiteral(valueToString(t.value())), true);
            }
            return new TermQuery(new Term(t.fieldName(), valueToString(t.value())));
        }
        if (builder instanceof TermsQueryBuilder t) {
            if (t.lookup() != null) {
                throw new IllegalArgumentException("terms lookup queries are not supported by this converter");
            }
            BooleanQuery.Builder b = BooleanQuery.builder();
            for (Object v : t.values()) {
                b.add(new TermQuery(new Term(t.fieldName(), valueToString(v))), BooleanQuery.Occur.SHOULD);
            }
            return b.build();
        }
        if (builder instanceof MatchQueryBuilder m) {
            return convertMatch(m);
        }
        if (builder instanceof MatchPhraseQueryBuilder m) {
            return convertMatchPhrase(m.fieldName(), valueToString(m.query()), m.slop());
        }
        if (builder instanceof MatchPhrasePrefixQueryBuilder m) {
            Map<String, Object> params = extractParams(m, m.fieldName());
            int slop = params.containsKey("slop") ? QueryParseUtils.asInt(params.get("slop")) : 0;
            int maxExpansions = params.containsKey("max_expansions") ? QueryParseUtils.asInt(params.get("max_expansions")) : 50;
            return convertMatchPhrasePrefix(m.fieldName(), valueToString(m.query()), slop, maxExpansions);
        }
        if (builder instanceof MatchBoolPrefixQueryBuilder m) {
            Map<String, Object> params = extractParams(m, m.fieldName());
            Operator operator = params.containsKey("operator") ? Operator.fromString((String) params.get("operator")) : Operator.OR;
            int maxExpansions = params.containsKey("max_expansions") ? QueryParseUtils.asInt(params.get("max_expansions")) : 50;
            return convertMatchBoolPrefix(m.fieldName(), valueToString(m.query()), operator, maxExpansions);
        }
        if (builder instanceof MultiMatchQueryBuilder m) {
            return convertMultiMatch(m);
        }
        if (builder instanceof CombinedFieldsQueryBuilder c) {
            return convertCombinedFields(c);
        }
        if (builder instanceof QueryStringQueryBuilder q) {
            return convert(q.toLuceneQuery(), ctx);
        }
        if (builder instanceof SimpleQueryStringQueryBuilder q) {
            return convert(q.toLuceneQuery(), ctx);
        }
        if (builder instanceof BoolQueryBuilder bq) {
            return convertBool(bq, ctx);
        }
        if (builder instanceof ConstantScoreQueryBuilder csq) {
            return new ConstantScoreQuery(convert(csq.innerQuery(), ctx));
        }
        if (builder instanceof RangeQueryBuilder r) {
            return convertRange(r);
        }
        if (builder instanceof PrefixQueryBuilder p) {
            return convertPrefix(p);
        }
        if (builder instanceof WildcardQueryBuilder w) {
            Map<String, Object> params = extractParams(w, w.fieldName());
            boolean caseInsensitive = params.containsKey("case_insensitive") && QueryParseUtils.asBoolean(params.get("case_insensitive"));
            return AutomatonQuery.wildcard(w.fieldName(), w.value(), caseInsensitive);
        }
        if (builder instanceof RegexpQueryBuilder r) {
            Map<String, Object> params = extractParams(r, r.fieldName());
            String flagsSpec = params.containsKey("flags") ? String.valueOf(params.get("flags")) : "ALL";
            boolean caseInsensitive = params.containsKey("case_insensitive") && QueryParseUtils.asBoolean(params.get("case_insensitive"));
            int flags = parseRegexpFlags(flagsSpec) | (caseInsensitive ? RegExp.CASE_INSENSITIVE : 0);
            return AutomatonQuery.regexp(r.fieldName(), r.value(), flags);
        }
        if (builder instanceof FuzzyQueryBuilder f) {
            String text = valueToString(f.value());
            int maxEdits = f.fuzziness().asDistance(text);
            Map<String, Object> params = extractParams(f, f.fieldName());
            int prefixLength = params.containsKey("prefix_length") ? QueryParseUtils.asInt(params.get("prefix_length")) : 0;
            boolean transpositions = !params.containsKey("transpositions") || QueryParseUtils.asBoolean(params.get("transpositions"));
            return AutomatonQuery.fuzzy(f.fieldName(), text, maxEdits, Math.min(Math.max(prefixLength, 0), text.length()), transpositions);
        }
        if (builder instanceof ExistsQueryBuilder e) {
            return new FieldExistsQuery(e.fieldName());
        }
        if (builder instanceof IdsQueryBuilder i) {
            if (i.values().isEmpty()) {
                return new MatchNoDocsQuery();
            }
            BooleanQuery.Builder b = BooleanQuery.builder();
            for (String id : i.values()) {
                b.add(new TermQuery(new Term("_id", id)), BooleanQuery.Occur.SHOULD);
            }
            b.setMinimumShouldMatch(1);
            return b.build();
        }
        if (builder instanceof DisMaxQueryBuilder d) {
            List<Query> qs = new ArrayList<>();
            for (QueryBuilder q : d.queries()) {
                qs.add(convert(q, ctx));
            }
            if (qs.isEmpty()) {
                return new MatchNoDocsQuery();
            }
            return new DisjunctionMaxQuery(qs, d.tieBreaker() == null ? 0f : d.tieBreaker());
        }
        if (builder instanceof BoostingQueryBuilder bo) {
            return new BoostingQuery(convert(bo.positive(), ctx), convert(bo.negative(), ctx), bo.negativeBoost());
        }
        if (builder instanceof ScriptQueryBuilder sq) {
            return new ScriptFilterQuery(sq.script(), ctx.scriptService());
        }
        if (builder instanceof ScriptScoreQueryBuilder ss) {
            Query inner = convert(ss.query(), ctx);
            FunctionSpec spec = new FunctionSpec.ScriptScore(ss.script(), ctx.scriptService());
            List<CompositeFunctionScoreQuery.FunctionEntry> entries =
                List.of(new CompositeFunctionScoreQuery.FunctionEntry(null, spec, null));
            return new CompositeFunctionScoreQuery(inner, entries, CompositeFunctionScoreQuery.ScoreCombine.MULTIPLY,
                CompositeFunctionScoreQuery.BoostCombine.REPLACE, null, ss.minScore());
        }
        if (builder instanceof FunctionScoreQueryBuilder fs) {
            return convertFunctionScore(fs, ctx);
        }
        if (builder instanceof KnnQueryBuilder k) {
            if (ctx.vectorProvider() == null) {
                throw new IllegalArgumentException("knn queries require a VectorSegmentAccessorProvider supplied via ConversionContext");
            }
            float[] vector = new float[k.queryVector().size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = k.queryVector().get(i);
            }
            Query filter = k.filter() == null ? null : convert(k.filter(), ctx);
            Map<String, Object> params = extractFlatParams(k);
            int numCandidates = params.containsKey("num_candidates") ? QueryParseUtils.asInt(params.get("num_candidates")) : k.k();
            return new KnnVectorQuery(k.field(), vector, k.k(), numCandidates, filter, ctx.vectorProvider());
        }
        if (builder instanceof WrapperQueryBuilder w) {
            Object decoded = IngestJsonParser.parse(w.decodedSource());
            if (!(decoded instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("wrapper query source must decode to a JSON object");
            }
            return convert(QueryParser.parseQuery((Map<String, Object>) map), ctx);
        }
        if (builder instanceof PinnedQueryBuilder p) {
            return new PinnedQuery(p.ids(), convert(p.organic(), ctx));
        }
        if (builder instanceof NestedQueryBuilder n) {
            requireParentChildMapping(ctx, "nested");
            JoinScoreMode mode = switch (n.scoreMode()) {
                case AVG -> JoinScoreMode.AVG;
                case SUM -> JoinScoreMode.SUM;
                case MIN -> JoinScoreMode.MIN;
                case MAX -> JoinScoreMode.MAX;
                case NONE -> JoinScoreMode.NONE;
            };
            if (ctx.parentChildMapping() instanceof com.naqqa.elasticsearch.index.engine.segment.EngineNestedDocMapping engine) {
                com.naqqa.elasticsearch.index.engine.segment.EngineNestedDocMapping scoped = engine.path() == null
                    ? engine.forPath(n.path()) : engine.forPath(n.path(), engine.path());
                Query child = convert(n.query(), ctx.withParentChildMapping(scoped));
                return scoped.nestedQuery(child, mode);
            }
            return new NestedQuery(convert(n.query(), ctx), ctx.parentChildMapping(), mode);
        }
        if (builder instanceof HasChildQueryBuilder h) {
            requireParentChildMapping(ctx, "has_child");
            return buildHasChild(h, ctx);
        }
        if (builder instanceof HasParentQueryBuilder h) {
            requireParentChildMapping(ctx, "has_parent");
            return new HasParentQuery(convert(h.query(), ctx), ctx.parentChildMapping(), extractHasParentScore(h));
        }
        if (builder instanceof ParentIdQueryBuilder p) {
            requireParentChildMapping(ctx, "parent_id");
            return new ParentIdQuery(p.id(), ctx.parentChildMapping());
        }
        if (builder instanceof GeoBoundingBoxQueryBuilder g) {
            GeoPoint topLeft = g.topLeft();
            GeoPoint bottomRight = g.bottomRight();
            return new GeoBoundingBoxQuery(g.fieldName(), bottomRight.lat(), topLeft.lat(), topLeft.lon(), bottomRight.lon());
        }
        if (builder instanceof GeoDistanceQueryBuilder g) {
            double meters = DistanceUnit.parseToMeters(g.distance());
            Map<String, Object> params = extractFlatParams(g);
            GeoDistance type = "plane".equalsIgnoreCase(String.valueOf(params.get("distance_type"))) ? GeoDistance.PLANE : GeoDistance.ARC;
            return new GeoDistanceQuery(g.fieldName(), g.point().lat(), g.point().lon(), meters, type);
        }
        if (builder instanceof GeoPolygonQueryBuilder g) {
            return new GeoPolygonQuery(g.fieldName(), g.points());
        }
        if (builder instanceof GeoShapeQueryBuilder g) {
            Geometry shape = g.shape();
            if (shape == null) {
                throw new IllegalArgumentException("indexed_shape lookups are not supported by this converter");
            }
            return new GeoShapeQuery(g.fieldName(), shape, mapShapeRelation(extractShapeRelation(g)));
        }
        if (builder instanceof ShapeQueryBuilder g) {
            Geometry shape = g.shape();
            if (shape == null) {
                throw new IllegalArgumentException("indexed_shape lookups are not supported by this converter");
            }
            return new GeoShapeQuery(g.fieldName(), shape, mapShapeRelation(extractShapeRelation(g)));
        }
        if (builder instanceof SpanQueryBuilder sb) {
            return convertSpan(sb, ctx);
        }
        throw new IllegalArgumentException("unsupported query type for conversion to an executable Query: ["
            + builder.getWriteableName() + "]");
    }

    private static void requireParentChildMapping(ConversionContext ctx, String queryName) {
        if (ctx.parentChildMapping() == null) {
            throw new IllegalArgumentException("[" + queryName + "] queries require a ParentChildDocMapping supplied via ConversionContext");
        }
        if (!"nested".equals(queryName)
            && ctx.parentChildMapping() instanceof com.naqqa.elasticsearch.index.engine.segment.EngineNestedDocMapping) {
            throw new IllegalArgumentException("[" + queryName + "] queries require a join field mapping which is not available");
        }
    }

    private static boolean extractHasParentScore(HasParentQueryBuilder h) {
        Map<String, Object> outer = h.toMap();
        Object inner = outer.values().iterator().next();
        if (inner instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("score"))) {
            return true;
        }
        return false;
    }

    private static Query buildHasChild(HasChildQueryBuilder h, ConversionContext ctx) {
        Map<String, Object> outer = h.toMap();
        Object inner = outer.values().iterator().next();
        Integer minChildren = null;
        Integer maxChildren = null;
        JoinScoreMode mode = JoinScoreMode.NONE;
        if (inner instanceof Map<?, ?> m) {
            if (m.get("min_children") != null) {
                minChildren = QueryParseUtils.asInt(m.get("min_children"));
            }
            if (m.get("max_children") != null) {
                maxChildren = QueryParseUtils.asInt(m.get("max_children"));
            }
            Object sm = m.get("score_mode");
            if (sm != null) {
                mode = switch (((String) sm).toUpperCase(Locale.ROOT)) {
                    case "AVG" -> JoinScoreMode.AVG;
                    case "SUM" -> JoinScoreMode.SUM;
                    case "MIN" -> JoinScoreMode.MIN;
                    case "MAX" -> JoinScoreMode.MAX;
                    default -> JoinScoreMode.NONE;
                };
            }
        }
        return new HasChildQuery(convert(h.query(), ctx), ctx.parentChildMapping(), mode, minChildren, maxChildren);
    }

    private static GeoShapeQueryBuilder.ShapeRelation extractShapeRelation(GeoShapeQueryBuilder g) {
        Map<String, Object> outer = g.toMap();
        Object inner = outer.values().iterator().next();
        if (inner instanceof Map<?, ?> m) {
            Object fieldParams = m.get(g.fieldName());
            if (fieldParams instanceof Map<?, ?> fp && fp.get("relation") != null) {
                return GeoShapeQueryBuilder.ShapeRelation.valueOf(((String) fp.get("relation")).toUpperCase(Locale.ROOT));
            }
        }
        return GeoShapeQueryBuilder.ShapeRelation.INTERSECTS;
    }

    private static ShapeQueryBuilder.ShapeRelation extractShapeRelation(ShapeQueryBuilder g) {
        Map<String, Object> outer = g.toMap();
        Object inner = outer.values().iterator().next();
        if (inner instanceof Map<?, ?> m) {
            Object fieldParams = m.get(g.fieldName());
            if (fieldParams instanceof Map<?, ?> fp && fp.get("relation") != null) {
                return ShapeQueryBuilder.ShapeRelation.valueOf(((String) fp.get("relation")).toUpperCase(Locale.ROOT));
            }
        }
        return ShapeQueryBuilder.ShapeRelation.INTERSECTS;
    }

    private static GeoShapeRelations.Kind mapShapeRelation(GeoShapeQueryBuilder.ShapeRelation r) {
        return switch (r) {
            case INTERSECTS -> GeoShapeRelations.Kind.INTERSECTS;
            case DISJOINT -> GeoShapeRelations.Kind.DISJOINT;
            case WITHIN -> GeoShapeRelations.Kind.WITHIN;
            case CONTAINS -> GeoShapeRelations.Kind.CONTAINS;
        };
    }

    private static GeoShapeRelations.Kind mapShapeRelation(ShapeQueryBuilder.ShapeRelation r) {
        return switch (r) {
            case INTERSECTS -> GeoShapeRelations.Kind.INTERSECTS;
            case DISJOINT -> GeoShapeRelations.Kind.DISJOINT;
            case WITHIN -> GeoShapeRelations.Kind.WITHIN;
            case CONTAINS -> GeoShapeRelations.Kind.CONTAINS;
        };
    }

    private static SpanQuery convertSpan(SpanQueryBuilder builder, ConversionContext ctx) {
        if (builder instanceof SpanTermQueryBuilder t) {
            return new SpanTermQuery(new Term(t.fieldName(), valueToString(t.value())));
        }
        if (builder instanceof SpanNearQueryBuilder n) {
            List<SpanQuery> clauses = new ArrayList<>();
            for (SpanQueryBuilder c : n.clauses()) {
                clauses.add(convertSpan(c, ctx));
            }
            return new SpanNearQuery(clauses, n.slop(), n.inOrder());
        }
        if (builder instanceof SpanOrQueryBuilder o) {
            List<SpanQuery> clauses = new ArrayList<>();
            for (SpanQueryBuilder c : o.clauses()) {
                clauses.add(convertSpan(c, ctx));
            }
            return new SpanOrQuery(clauses);
        }
        if (builder instanceof SpanNotQueryBuilder n) {
            Map<String, Object> outer = n.toMap();
            Object inner = outer.values().iterator().next();
            int pre = 0;
            int post = 0;
            if (inner instanceof Map<?, ?> m) {
                if (m.get("dist") != null) {
                    pre = post = QueryParseUtils.asInt(m.get("dist"));
                } else {
                    if (m.get("pre") != null) {
                        pre = QueryParseUtils.asInt(m.get("pre"));
                    }
                    if (m.get("post") != null) {
                        post = QueryParseUtils.asInt(m.get("post"));
                    }
                }
            }
            return new SpanNotQuery(convertSpan(n.include(), ctx), convertSpan(n.exclude(), ctx), pre, post);
        }
        if (builder instanceof SpanFirstQueryBuilder f) {
            return new SpanFirstQuery(convertSpan(f.match(), ctx), f.end());
        }
        if (builder instanceof SpanContainingQueryBuilder c) {
            return new SpanContainingQuery(convertSpan(c.little(), ctx), convertSpan(c.big(), ctx));
        }
        if (builder instanceof SpanWithinQueryBuilder w) {
            return new SpanWithinQuery(convertSpan(w.little(), ctx), convertSpan(w.big(), ctx));
        }
        if (builder instanceof SpanMultiTermQueryBuilder m) {
            Query matchQuery = convert(m.match(), ctx);
            String field = fieldOf(m.match());
            return new SpanMultiTermQueryWrapper(matchQuery, field);
        }
        if (builder instanceof FieldMaskingSpanQueryBuilder f) {
            return new FieldMaskingSpanQuery(convertSpan(f.query(), ctx), f.field());
        }
        throw new IllegalArgumentException("unsupported span query type: [" + builder.getWriteableName() + "]");
    }

    private static String fieldOf(QueryBuilder builder) {
        if (builder instanceof PrefixQueryBuilder p) {
            return p.fieldName();
        }
        if (builder instanceof WildcardQueryBuilder w) {
            return w.fieldName();
        }
        if (builder instanceof RegexpQueryBuilder r) {
            return r.fieldName();
        }
        if (builder instanceof FuzzyQueryBuilder f) {
            return f.fieldName();
        }
        if (builder instanceof TermQueryBuilder t) {
            return t.fieldName();
        }
        throw new IllegalArgumentException("span_multi match query must be a single-field query");
    }

    private static Query convertFunctionScore(FunctionScoreQueryBuilder fs, ConversionContext ctx) {
        Query inner = convert(fs.query(), ctx);
        List<CompositeFunctionScoreQuery.FunctionEntry> entries = new ArrayList<>();
        for (FunctionScoreQueryBuilder.FilterFunctionBuilder ff : fs.functions()) {
            Query filter = ff.filter() == null ? null : convert(ff.filter(), ctx);
            FunctionSpec spec = toFunctionSpec(ff.function(), ctx);
            entries.add(new CompositeFunctionScoreQuery.FunctionEntry(filter, spec, ff.weight()));
        }
        CompositeFunctionScoreQuery.ScoreCombine scoreCombine = switch (fs.scoreMode()) {
            case MULTIPLY -> CompositeFunctionScoreQuery.ScoreCombine.MULTIPLY;
            case SUM -> CompositeFunctionScoreQuery.ScoreCombine.SUM;
            case AVG -> CompositeFunctionScoreQuery.ScoreCombine.AVG;
            case FIRST -> CompositeFunctionScoreQuery.ScoreCombine.FIRST;
            case MAX -> CompositeFunctionScoreQuery.ScoreCombine.MAX;
            case MIN -> CompositeFunctionScoreQuery.ScoreCombine.MIN;
        };
        CompositeFunctionScoreQuery.BoostCombine boostCombine = switch (fs.boostMode()) {
            case MULTIPLY -> CompositeFunctionScoreQuery.BoostCombine.MULTIPLY;
            case REPLACE -> CompositeFunctionScoreQuery.BoostCombine.REPLACE;
            case SUM -> CompositeFunctionScoreQuery.BoostCombine.SUM;
            case AVG -> CompositeFunctionScoreQuery.BoostCombine.AVG;
            case MAX -> CompositeFunctionScoreQuery.BoostCombine.MAX;
            case MIN -> CompositeFunctionScoreQuery.BoostCombine.MIN;
        };
        Map<String, Object> outer = fs.toMap();
        Object innerMap = outer.values().iterator().next();
        Float maxBoost = null;
        Float minScore = null;
        if (innerMap instanceof Map<?, ?> m) {
            if (m.get("max_boost") != null) {
                maxBoost = QueryParseUtils.asFloat(m.get("max_boost"));
            }
            if (m.get("min_score") != null) {
                minScore = QueryParseUtils.asFloat(m.get("min_score"));
            }
        }
        return new CompositeFunctionScoreQuery(inner, entries, scoreCombine, boostCombine, maxBoost, minScore);
    }

    private static FunctionSpec toFunctionSpec(ScoreFunctionBuilder fb, ConversionContext ctx) {
        if (fb instanceof WeightScoreFunction w) {
            return new FunctionSpec.Weight(w.weight());
        }
        if (fb instanceof RandomScoreFunction r) {
            return new FunctionSpec.RandomScore(r.seed());
        }
        if (fb instanceof ScriptScoreFunction s) {
            return new FunctionSpec.ScriptScore(s.script(), ctx.scriptService());
        }
        if (fb instanceof FieldValueFactorScoreFunction fvf) {
            Map<String, Object> outer = fvf.toMap();
            Map<String, Object> params = asStringMap(outer.get(FieldValueFactorScoreFunction.NAME));
            String field = (String) params.get("field");
            float factor = params.containsKey("factor") ? QueryParseUtils.asFloat(params.get("factor")) : 1.0f;
            String modifier = params.containsKey("modifier") ? String.valueOf(params.get("modifier")) : "none";
            Double missing = params.containsKey("missing") ? QueryParseUtils.asDouble(params.get("missing")) : null;
            return new FunctionSpec.FieldValueFactor(field, factor, modifier, missing);
        }
        if (fb instanceof DecayScoreFunction decay) {
            Map<String, Object> outer = decay.toMap();
            Object fieldMapObj = outer.values().iterator().next();
            Map<String, Object> fieldMap = asStringMap(fieldMapObj);
            Map<String, Object> params = asStringMap(fieldMap.get(decay.field()));
            double origin = params.containsKey("origin") ? QueryParseUtils.asDouble(params.get("origin")) : 0.0;
            double scale = QueryParseUtils.asDouble(decay.scale());
            double offset = params.containsKey("offset") ? QueryParseUtils.asDouble(params.get("offset")) : 0.0;
            double decayValue = params.containsKey("decay") ? QueryParseUtils.asDouble(params.get("decay")) : 0.5;
            return new FunctionSpec.Decay(decay.decayType().name(), decay.field(), origin, scale, offset, decayValue);
        }
        throw new IllegalArgumentException("unsupported score function type: [" + fb.getName() + "]");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractParams(AbstractQueryBuilder builder, String fieldName) {
        Map<String, Object> outer = builder.toMap();
        Object outerVal = outer.values().iterator().next();
        if (outerVal instanceof Map<?, ?> m) {
            Object inner = m.get(fieldName);
            if (inner instanceof Map<?, ?>) {
                return asStringMap(inner);
            }
        }
        return Map.of();
    }

    private static Map<String, Object> extractFlatParams(AbstractQueryBuilder builder) {
        Map<String, Object> outer = builder.toMap();
        return asStringMap(outer.values().iterator().next());
    }

    private static long asLongValue(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v).trim());
    }

    private static Query convertPrefix(PrefixQueryBuilder p) {
        Map<String, Object> params = extractParams(p, p.fieldName());
        boolean caseInsensitive = params.containsKey("case_insensitive") && QueryParseUtils.asBoolean(params.get("case_insensitive"));
        if (caseInsensitive) {
            return AutomatonQuery.wildcard(p.fieldName(), escapeWildcardLiteral(p.value()) + "*", true);
        }
        return new PrefixQuery(p.fieldName(), p.value().getBytes(StandardCharsets.UTF_8));
    }

    private static Query convertRange(RangeQueryBuilder r) {
        Object from = r.from();
        Object to = r.to();
        if (from == null && to == null) {
            return new MatchAllDocsQuery();
        }
        if (isNumericBound(from) && isNumericBound(to)) {
            boolean integral = isIntegral(from) && isIntegral(to);
            if (integral) {
                long lo = from == null ? Long.MIN_VALUE : asLongValue(from);
                long hi = to == null ? Long.MAX_VALUE : asLongValue(to);
                if (from != null && !r.includeLower()) {
                    lo = lo + 1;
                }
                if (to != null && !r.includeUpper()) {
                    hi = hi - 1;
                }
                return PointRangeQuery.newLongRange(r.fieldName(), lo, hi);
            }
            double lo = from == null ? Double.NEGATIVE_INFINITY : QueryParseUtils.asDouble(from);
            double hi = to == null ? Double.POSITIVE_INFINITY : QueryParseUtils.asDouble(to);
            if (from != null && !r.includeLower()) {
                lo = Math.nextUp(lo);
            }
            if (to != null && !r.includeUpper()) {
                hi = Math.nextDown(hi);
            }
            return PointRangeQuery.newDoubleRange(r.fieldName(), lo, hi);
        }
        byte[] lowerBytes = from == null ? null : valueToString(from).getBytes(StandardCharsets.UTF_8);
        byte[] upperBytes = to == null ? null : valueToString(to).getBytes(StandardCharsets.UTF_8);
        return new TermRangeQuery(r.fieldName(), lowerBytes, upperBytes, r.includeLower(), r.includeUpper());
    }

    private static boolean isNumericBound(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Number) {
            return true;
        }
        if (v instanceof String s) {
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isIntegral(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return true;
        }
        if (v instanceof Double || v instanceof Float) {
            return false;
        }
        String s = String.valueOf(v);
        return !s.contains(".") && !s.toLowerCase(Locale.ROOT).contains("e");
    }

    private static int parseRegexpFlags(String flags) {
        if (flags == null || flags.isEmpty() || "ALL".equalsIgnoreCase(flags)) {
            return RegExp.ALL;
        }
        int result = 0;
        for (String part : flags.split("\\|")) {
            result |= switch (part.trim().toUpperCase(Locale.ROOT)) {
                case "INTERSECTION" -> RegExp.INTERSECTION;
                case "COMPLEMENT" -> RegExp.COMPLEMENT;
                case "EMPTY" -> RegExp.EMPTY;
                case "ANYSTRING" -> RegExp.ANYSTRING;
                case "AUTOMATON" -> RegExp.AUTOMATON;
                case "INTERVAL" -> RegExp.INTERVAL;
                case "NONE" -> RegExp.NONE;
                case "ALL" -> RegExp.ALL;
                default -> 0;
            };
        }
        return result;
    }

    private static String escapeWildcardLiteral(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '*' || c == '?' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static Query convertMatch(MatchQueryBuilder m) {
        String text = valueToString(m.query());
        String[] tokens = tokenize(text);
        if (tokens.length == 0) {
            return m.zeroTermsQuery() == com.naqqa.elasticsearch.index.query.ZeroTermsQuery.ALL
                ? new MatchAllDocsQuery() : new MatchNoDocsQuery();
        }
        Map<String, Object> params = m.fuzziness() == null ? Map.of() : extractParams(m, m.fieldName());
        int prefixLength = params.containsKey("prefix_length") ? QueryParseUtils.asInt(params.get("prefix_length")) : 0;
        boolean transpositions = !params.containsKey("fuzzy_transpositions") || QueryParseUtils.asBoolean(params.get("fuzzy_transpositions"));
        if (tokens.length == 1) {
            return matchTermQuery(m.fieldName(), tokens[0], m.fuzziness(), prefixLength, transpositions);
        }
        BooleanQuery.Occur occur = m.operator() == Operator.AND ? BooleanQuery.Occur.MUST : BooleanQuery.Occur.SHOULD;
        BooleanQuery.Builder b = BooleanQuery.builder();
        for (String token : tokens) {
            b.add(matchTermQuery(m.fieldName(), token, m.fuzziness(), prefixLength, transpositions), occur);
        }
        if (occur == BooleanQuery.Occur.SHOULD && m.minimumShouldMatch() != null) {
            b.setMinimumShouldMatch(m.minimumShouldMatch().resolve(tokens.length));
        }
        return b.build();
    }

    private static Query matchTermQuery(String field, String token, Fuzziness fuzziness, int prefixLength, boolean transpositions) {
        TermQuery exact = new TermQuery(new Term(field, token));
        if (fuzziness == null) {
            return exact;
        }
        int edits = fuzziness.asDistance(token);
        if (edits <= 0) {
            return exact;
        }
        int prefix = Math.min(Math.max(prefixLength, 0), token.length());
        BooleanQuery.Builder b = BooleanQuery.builder();
        b.add(exact, BooleanQuery.Occur.SHOULD);
        b.add(AutomatonQuery.fuzzy(field, token, edits, prefix, transpositions), BooleanQuery.Occur.SHOULD);
        b.setMinimumShouldMatch(1);
        return b.build();
    }

    private static Query convertMatchPhrase(String field, String text, int slop) {
        String[] tokens = tokenize(text);
        if (tokens.length == 0) {
            return new MatchNoDocsQuery();
        }
        if (tokens.length == 1) {
            return new TermQuery(new Term(field, tokens[0]));
        }
        List<byte[]> termBytes = new ArrayList<>(tokens.length);
        for (String t : tokens) {
            termBytes.add(t.getBytes(StandardCharsets.UTF_8));
        }
        return new PhraseQuery(field, termBytes, slop);
    }

    private static Query convertMatchPhrasePrefix(String field, String text, int slop, int maxExpansions) {
        String[] tokens = tokenize(text);
        if (tokens.length == 0) {
            return new MatchNoDocsQuery();
        }
        List<byte[]> prior = new ArrayList<>();
        for (int i = 0; i < tokens.length - 1; i++) {
            prior.add(tokens[i].getBytes(StandardCharsets.UTF_8));
        }
        byte[] prefix = tokens[tokens.length - 1].getBytes(StandardCharsets.UTF_8);
        return new PrefixPhraseQuery(field, prior, prefix, slop, maxExpansions);
    }

    private static Query convertMatchBoolPrefix(String field, String text, Operator operator, int maxExpansions) {
        String[] tokens = tokenize(text);
        if (tokens.length == 0) {
            return new MatchNoDocsQuery();
        }
        BooleanQuery.Occur occur = operator == Operator.AND ? BooleanQuery.Occur.MUST : BooleanQuery.Occur.SHOULD;
        BooleanQuery.Builder b = BooleanQuery.builder();
        for (int i = 0; i < tokens.length - 1; i++) {
            b.add(new TermQuery(new Term(field, tokens[i])), occur);
        }
        b.add(AutomatonQuery.wildcard(field, escapeWildcardLiteral(tokens[tokens.length - 1]) + "*", false), occur);
        if (occur == BooleanQuery.Occur.SHOULD) {
            b.setMinimumShouldMatch(1);
        }
        return b.build();
    }

    private static Query convertMultiMatch(MultiMatchQueryBuilder m) {
        String text = valueToString(m.query());
        Map<String, Object> params = extractFlatParams(m);
        Operator operator = params.containsKey("operator") ? Operator.fromString(String.valueOf(params.get("operator"))) : Operator.OR;
        int slop = params.containsKey("slop") ? QueryParseUtils.asInt(params.get("slop")) : 0;
        Float tieBreakerParam = params.containsKey("tie_breaker") ? QueryParseUtils.asFloat(params.get("tie_breaker")) : null;
        Fuzziness fuzziness = QueryParseUtils.asFuzziness(params.get("fuzziness"));
        int prefixLength = params.containsKey("prefix_length") ? QueryParseUtils.asInt(params.get("prefix_length")) : 0;
        boolean transpositions = !params.containsKey("fuzzy_transpositions") || QueryParseUtils.asBoolean(params.get("fuzzy_transpositions"));
        List<Query> perField = new ArrayList<>();
        for (String field : m.fields().isEmpty() ? List.of("_all") : new ArrayList<>(m.fields().keySet())) {
            Float boost = m.fields().get(field);
            Query fieldQuery = switch (m.type()) {
                case PHRASE -> convertMatchPhrase(field, text, slop);
                case PHRASE_PREFIX -> convertMatchPhrasePrefix(field, text, slop, 50);
                case BOOL_PREFIX -> convertMatchBoolPrefix(field, text, operator, 50);
                default -> convertMatchTerms(field, text, operator, fuzziness, prefixLength, transpositions);
            };
            if (boost != null) {
                fieldQuery = new BoostQuery(fieldQuery, boost);
            }
            perField.add(fieldQuery);
        }
        if (perField.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        if (perField.size() == 1) {
            return perField.get(0);
        }
        if (m.type() == MultiMatchQueryBuilder.Type.MOST_FIELDS) {
            BooleanQuery.Builder b = BooleanQuery.builder();
            for (Query q : perField) {
                b.add(q, BooleanQuery.Occur.SHOULD);
            }
            b.setMinimumShouldMatch(1);
            return b.build();
        }
        float tieBreaker = tieBreakerParam != null ? tieBreakerParam : (m.type() == MultiMatchQueryBuilder.Type.CROSS_FIELDS ? 1.0f : 0.0f);
        return new DisjunctionMaxQuery(perField, tieBreaker);
    }

    private static Query convertMatchTerms(String field, String text, Operator operator, Fuzziness fuzziness,
                                           int prefixLength, boolean transpositions) {
        String[] tokens = tokenize(text);
        if (tokens.length == 0) {
            return new MatchNoDocsQuery();
        }
        if (tokens.length == 1) {
            return matchTermQuery(field, tokens[0], fuzziness, prefixLength, transpositions);
        }
        BooleanQuery.Occur occur = operator == Operator.AND ? BooleanQuery.Occur.MUST : BooleanQuery.Occur.SHOULD;
        BooleanQuery.Builder b = BooleanQuery.builder();
        for (String token : tokens) {
            b.add(matchTermQuery(field, token, fuzziness, prefixLength, transpositions), occur);
        }
        if (occur == BooleanQuery.Occur.SHOULD) {
            b.setMinimumShouldMatch(1);
        }
        return b.build();
    }

    private static Query convertCombinedFields(CombinedFieldsQueryBuilder c) {
        String text = valueToString(c.query());
        String[] tokens = tokenize(text);
        if (tokens.length == 0) {
            return new MatchNoDocsQuery();
        }
        List<String> fields = c.fields().isEmpty() ? List.of("_all") : new ArrayList<>(c.fields().keySet());
        Map<String, Object> params = extractFlatParams(c);
        Operator operator = params.containsKey("operator") ? Operator.fromString(String.valueOf(params.get("operator"))) : Operator.OR;
        BooleanQuery.Occur tokenOccur = operator == Operator.AND ? BooleanQuery.Occur.MUST : BooleanQuery.Occur.SHOULD;
        BooleanQuery.Builder outer = BooleanQuery.builder();
        for (String token : tokens) {
            BooleanQuery.Builder perToken = BooleanQuery.builder();
            for (String field : fields) {
                perToken.add(new TermQuery(new Term(field, token)), BooleanQuery.Occur.SHOULD);
            }
            perToken.setMinimumShouldMatch(1);
            outer.add(perToken.build(), tokenOccur);
        }
        if (tokenOccur == BooleanQuery.Occur.SHOULD) {
            outer.setMinimumShouldMatch(1);
        }
        return outer.build();
    }

    private static Query convertBool(BoolQueryBuilder bq, ConversionContext ctx) {
        BooleanQuery.Builder b = BooleanQuery.builder();
        for (QueryBuilder q : bq.must()) {
            b.add(convert(q, ctx), BooleanQuery.Occur.MUST);
        }
        for (QueryBuilder q : bq.filter()) {
            b.add(convert(q, ctx), BooleanQuery.Occur.FILTER);
        }
        for (QueryBuilder q : bq.should()) {
            b.add(convert(q, ctx), BooleanQuery.Occur.SHOULD);
        }
        for (QueryBuilder q : bq.mustNot()) {
            b.add(convert(q, ctx), BooleanQuery.Occur.MUST_NOT);
        }
        MinimumShouldMatch msm = bq.minimumShouldMatch();
        if (msm != null) {
            b.setMinimumShouldMatch(msm.resolve(bq.should().size()));
        }
        return b.build();
    }

    private static String[] tokenize(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.toLowerCase(Locale.ROOT).split("\\s+");
    }

    private static String valueToString(Object value) {
        return String.valueOf(value);
    }
}
