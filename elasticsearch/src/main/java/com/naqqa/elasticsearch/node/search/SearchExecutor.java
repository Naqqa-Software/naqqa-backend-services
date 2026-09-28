package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.cluster.routing.ShardId;
import com.naqqa.elasticsearch.common.regex.Regex;
import com.naqqa.elasticsearch.index.engine.EngineSearcher;
import com.naqqa.elasticsearch.index.engine.segment.StoredDocCodec;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.script.DocLookup;
import com.naqqa.elasticsearch.script.Script;
import com.naqqa.elasticsearch.script.ScriptContext;
import com.naqqa.elasticsearch.script.ScriptDocValues;
import com.naqqa.elasticsearch.script.ScriptService;
import com.naqqa.elasticsearch.search.advanced.profile.ProfileResult;
import com.naqqa.elasticsearch.search.advanced.profile.Profiler;
import com.naqqa.elasticsearch.search.advanced.requests.ExplainRenderer;
import com.naqqa.elasticsearch.search.advanced.rrf.RrfRetriever;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.ReduceContext;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.TopScoreDocCollector;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.BoostQuery;
import com.naqqa.elasticsearch.search.query.MatchNoDocsQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.vectors.query.KnnVectorQuery;
import com.naqqa.elasticsearch.search.vectors.query.VectorSegmentAccessorProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

final class SearchExecutor {

    private record KnnRequest(String field, float[] vector, int k, int numCandidates, Query filter, float boost) {
    }

    private record Retrieved(List<QueryPhase.Candidate> hits, long total) {
    }

    private record InnerHitsSpec(String name, int from, int size, QueryPhase.Sorter sorter) {
    }

    private static final QueryPhase.Sorter SCORE_SORTER = new QueryPhase.Sorter(List.of());

    private final SearchEngine engine;

    SearchExecutor(SearchEngine engine) {
        this.engine = engine;
    }

    Map<String, Object> execute(List<String> indices, Map<ShardId, EngineSearcher> searchers, SearchSpec spec,
                                Map<String, Object> aliasFilter) throws IOException {
        long startNanos = System.nanoTime();
        Function<String, QueryFactory.FieldType> allTypes = engine.fieldTypes(indices);
        List<ShardTarget> shards = new ArrayList<>();
        int ordinal = 0;
        for (Map.Entry<ShardId, EngineSearcher> e : searchers.entrySet()) {
            String index = e.getKey().index();
            shards.add(new ShardTarget(ordinal++, e.getKey(), e.getValue(), engine.indicesService().indexService(index),
                engine.fieldTypes(List.of(index))));
        }
        applyIndexBoosts(spec, shards);
        validateSorts(spec, shards);
        Function<Map<String, Object>, Query> parse = clause -> engine.queryFactory().toQuery(clause, allTypes);
        Map<String, Object> mainClause = withFilter(spec.hasQuery ? spec.queryClause : null, aliasFilter);
        Query mainQuery = parse.apply(mainClause);
        Query userQuery = spec.hasQuery ? parse.apply(spec.queryClause) : null;
        QueryPhase.Sorter sorter = new QueryPhase.Sorter(spec.sorts);
        QueryPhase.Options options = new QueryPhase.Options();
        options.postFilter = spec.postFilter == null ? null : parse.apply(spec.postFilter);
        options.minScore = spec.minScore;
        options.terminateAfter = spec.terminateAfter;
        options.deadlineNanos = spec.timeoutMillis >= 0 ? startNanos + Math.max(1, spec.timeoutMillis) * 1_000_000L : 0;
        options.searchAfter = spec.searchAfter == null ? null : sorter.convertAfter(spec.searchAfter);
        options.aggs = spec.aggs;
        String collapseField = null;
        List<InnerHitsSpec> innerHits = List.of();
        if (spec.collapse != null) {
            collapseField = String.valueOf(spec.collapse.get("field"));
            QueryFactory.FieldType type = allTypes.apply(collapseField);
            FieldValues.Kind kind = FieldValues.kind(type);
            if (type == null) {
                throw new RestApiException(400, "no mapping found for `" + collapseField + "` in order to collapse on");
            }
            if (kind == FieldValues.Kind.NONE || kind == FieldValues.Kind.BOOLEAN) {
                throw new RestApiException(400, "collapse is not supported for the field [" + collapseField + "] of the type ["
                    + type.type() + "]");
            }
            options.collapseField = collapseField;
            innerHits = parseInnerHits(spec.collapse.get("inner_hits"));
        }

        List<QueryPhase.Candidate> ordered;
        Map<Object, List<QueryPhase.Candidate>> groups = null;
        long totalHits = 0;
        boolean timedOut = false;
        boolean terminatedEarly = false;
        List<InternalAggregations> shardAggs = new ArrayList<>();
        int topN = spec.from + spec.size;
        if (spec.retriever != null) {
            for (ShardTarget shard : shards) {
                shard.query = mainQuery;
            }
            Retrieved retrieved = retrieve(spec.retriever, shards, spec, aliasFilter, parse, allTypes, Math.max(topN, 10));
            ordered = retrieved.hits();
            totalHits = retrieved.total();
        } else {
            List<Map<Integer, Map<Integer, Float>>> knnHits = new ArrayList<>();
            for (Map<String, Object> knn : spec.knn) {
                knnHits.add(knnPhase(parseKnn(knn, spec.size, shards, aliasFilter, parse), shards));
            }
            for (ShardTarget shard : shards) {
                Query query = mainQuery;
                if (!knnHits.isEmpty()) {
                    BooleanQuery.Builder b = BooleanQuery.builder();
                    int clauses = 0;
                    if (spec.hasQuery) {
                        b.add(mainQuery, BooleanQuery.Occur.SHOULD);
                        clauses++;
                    }
                    for (Map<Integer, Map<Integer, Float>> hits : knnHits) {
                        Map<Integer, Float> docs = hits.get(shard.ordinal);
                        if (docs != null && !docs.isEmpty()) {
                            b.add(new ScoredDocsQuery("knn", docs), BooleanQuery.Occur.SHOULD);
                            clauses++;
                        }
                    }
                    b.setMinimumShouldMatch(1);
                    query = clauses == 0 ? new MatchNoDocsQuery() : b.build();
                }
                if (shard.indexBoost != 1f) {
                    query = new BoostQuery(query, shard.indexBoost);
                }
                shard.query = query;
            }
            int perShard = topN;
            for (Map<String, Object> r : spec.rescore) {
                perShard = Math.max(perShard, SearchEngine.intValue(r.get("window_size"), 10));
            }
            List<QueryPhase.Candidate> merged = new ArrayList<>();
            Map<Object, List<QueryPhase.Candidate>> mergedGroups = collapseField == null ? null : new LinkedHashMap<>();
            for (ShardTarget shard : shards) {
                QueryPhase.ShardResult result = QueryPhase.execute(shard, shard.query, sorter, perShard, options);
                if (!spec.rescore.isEmpty()) {
                    rescore(result, shard, spec.rescore, parse);
                }
                totalHits += result.totalHits;
                timedOut |= result.timedOut;
                terminatedEarly |= result.terminatedEarly;
                if (result.aggregations != null) {
                    shardAggs.add(result.aggregations);
                }
                if (mergedGroups != null && result.groups != null) {
                    for (Map.Entry<Object, List<QueryPhase.Candidate>> g : result.groups.entrySet()) {
                        mergedGroups.computeIfAbsent(g.getKey(), k -> new ArrayList<>()).addAll(g.getValue());
                    }
                } else {
                    merged.addAll(result.top);
                }
            }
            if (mergedGroups != null) {
                for (List<QueryPhase.Candidate> members : mergedGroups.values()) {
                    members.sort(sorter);
                    merged.add(members.get(0));
                }
                groups = mergedGroups;
            }
            merged.sort(!spec.rescore.isEmpty() ? SCORE_SORTER : sorter);
            ordered = merged;
        }
        boolean scoresVisible = !spec.explicitSort || spec.trackScores || sorter.needsScores();
        Float maxScore = null;
        if (scoresVisible) {
            for (QueryPhase.Candidate c : ordered) {
                if (!Float.isNaN(c.score) && (maxScore == null || c.score > maxScore)) {
                    maxScore = c.score;
                }
            }
        }
        HighlightPhase highlight = spec.highlight == null ? null : new HighlightPhase(spec.highlight, parse);
        Map<Integer, Map<String, String>> mappedFieldsCache = new HashMap<>();
        List<Object> hitList = new ArrayList<>();
        for (int i = spec.from; i < ordered.size() && hitList.size() < spec.size; i++) {
            QueryPhase.Candidate c = ordered.get(i);
            Map<String, Object> hit = renderHit(c, spec, sorter, scoresVisible, highlight, userQuery, mappedFieldsCache);
            if (collapseField != null) {
                if (c.collapseKey != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fields = (Map<String, Object>) hit.computeIfAbsent("fields", k -> new LinkedHashMap<String, Object>());
                    fields.put(collapseField, List.of(c.collapseKey));
                }
                if (!innerHits.isEmpty() && groups != null) {
                    List<QueryPhase.Candidate> members = groups.get(c.collapseKey == null ? QueryPhase.NullKey.INSTANCE : c.collapseKey);
                    hit.put("inner_hits", renderInnerHits(members == null ? List.of(c) : members, innerHits, spec, highlight,
                        userQuery, mappedFieldsCache));
                }
            }
            hitList.add(hit);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("took", (System.nanoTime() - startNanos) / 1_000_000L);
        out.put("timed_out", timedOut);
        if (spec.terminateAfter > 0) {
            out.put("terminated_early", terminatedEarly);
        }
        out.put("_shards", shardsSection(shards.size()));
        Map<String, Object> hits = new LinkedHashMap<>();
        if (spec.trackTotalHitsUpTo >= 0) {
            boolean capped = totalHits > spec.trackTotalHitsUpTo;
            long value = capped ? spec.trackTotalHitsUpTo : totalHits;
            if (spec.totalHitsAsInt) {
                hits.put("total", value);
            } else {
                Map<String, Object> total = new LinkedHashMap<>();
                total.put("value", value);
                total.put("relation", capped ? "gte" : "eq");
                hits.put("total", total);
            }
        }
        hits.put("max_score", maxScore);
        hits.put("hits", hitList);
        out.put("hits", hits);
        if (spec.aggs != null) {
            InternalAggregations reduced = InternalAggregations.reduceAll(shardAggs,
                ReduceContext.forFinalReduction(new MultiBucketConsumer(MultiBucketConsumer.DEFAULT_MAX_BUCKETS)));
            out.put("aggregations", reduced.toMap());
        }
        if (spec.suggest != null) {
            out.put("suggest", SuggestPhase.execute(shards, spec.suggest));
        }
        if (spec.profile) {
            out.put("profile", profile(shards, Math.max(1, topN)));
        }
        return out;
    }

    static Map<String, Object> shardsSection(int total) {
        Map<String, Object> shards = new LinkedHashMap<>();
        shards.put("total", total);
        shards.put("successful", total);
        shards.put("skipped", 0);
        shards.put("failed", 0);
        return shards;
    }

    static Map<String, Object> withFilter(Map<String, Object> clause, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return clause;
        }
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(clause == null || clause.isEmpty() ? Map.of("match_all", Map.of()) : clause));
        bool.put("filter", List.of(filter));
        return Map.of("bool", bool);
    }

    private void applyIndexBoosts(SearchSpec spec, List<ShardTarget> shards) {
        if (spec.indicesBoost.isEmpty()) {
            return;
        }
        Map<String, Float> byIndex = new HashMap<>();
        for (Map.Entry<String, Float> e : spec.indicesBoost) {
            List<String> resolved;
            try {
                resolved = engine.resolve(List.of(e.getKey()), Map.of("ignore_unavailable", "true"));
            } catch (RuntimeException ex) {
                resolved = List.of();
            }
            if (resolved.isEmpty() && !Regex.isSimpleMatchPattern(e.getKey())) {
                throw new RestApiException(404, "no such index [" + e.getKey() + "]");
            }
            for (String index : resolved) {
                byIndex.putIfAbsent(index, e.getValue());
            }
        }
        for (ShardTarget shard : shards) {
            Float boost = byIndex.get(shard.index());
            if (boost != null) {
                shard.indexBoost = boost;
            }
        }
    }

    private void validateSorts(SearchSpec spec, List<ShardTarget> shards) {
        for (SearchSpec.SortSpec sort : spec.sorts) {
            if (sort.type() != SearchSpec.SortType.FIELD) {
                continue;
            }
            boolean mapped = false;
            for (ShardTarget shard : shards) {
                if (shard.indexService != null) {
                    if (SearchEngine.fieldSortType(shard.indexService.mapperService(), sort.field()) != null) {
                        mapped = true;
                    }
                    QueryFactory.FieldType type = shard.fieldType(sort.field());
                    if (type != null && FieldValues.kind(type) == FieldValues.Kind.NONE) {
                        throw new RestApiException(400, "can't sort on field [" + sort.field() + "] of type [" + type.type() + "]");
                    }
                }
            }
            if (!mapped && sort.unmappedType() == null && !shards.isEmpty()) {
                throw new RestApiException(400, "No mapping found for [" + sort.field() + "] in order to sort on");
            }
        }
    }

    private List<InnerHitsSpec> parseInnerHits(Object value) {
        List<InnerHitsSpec> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        List<?> list = value instanceof List<?> l ? l : List.of(value);
        for (Object o : list) {
            Map<String, Object> m = SettingsMaps.asMap(o);
            if (m == null) {
                continue;
            }
            String name = m.get("name") == null ? null : String.valueOf(m.get("name"));
            if (name == null) {
                throw new RestApiException(400, "[inner_hits] requires a [name] when used with collapse");
            }
            List<Object> sortSpecs = new ArrayList<>();
            if (m.get("sort") instanceof List<?> sl) {
                sortSpecs.addAll(sl);
            } else if (m.get("sort") != null) {
                sortSpecs.add(m.get("sort"));
            }
            out.add(new InnerHitsSpec(name, SearchEngine.intValue(m.get("from"), 0), SearchEngine.intValue(m.get("size"), 3),
                new QueryPhase.Sorter(SearchSpec.parseSorts(sortSpecs))));
        }
        return out;
    }

    private Map<String, Object> renderInnerHits(List<QueryPhase.Candidate> members, List<InnerHitsSpec> specs, SearchSpec spec,
                                                HighlightPhase highlight, Query userQuery,
                                                Map<Integer, Map<String, String>> mappedFieldsCache) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (InnerHitsSpec inner : specs) {
            List<QueryPhase.Candidate> sorted = new ArrayList<>();
            for (QueryPhase.Candidate m : members) {
                LeafReaderContext ctx = m.shard.leafFor(m.doc);
                Object[] values = ctx == null ? new Object[inner.sorter().sorts.size()]
                    : inner.sorter().values(m.shard, ctx, m.doc - ctx.docBase(), m.score);
                sorted.add(new QueryPhase.Candidate(m.shard, m.doc, m.score, values, m.collapseKey));
            }
            sorted.sort(inner.sorter());
            boolean innerSorted = inner.sorter().sorts.size() != 1 || !inner.sorter().byScoreOnly();
            Float max = null;
            for (QueryPhase.Candidate c : sorted) {
                if (max == null || c.score > max) {
                    max = c.score;
                }
            }
            List<Object> hits = new ArrayList<>();
            for (int i = inner.from(); i < sorted.size() && hits.size() < inner.size(); i++) {
                QueryPhase.Candidate c = sorted.get(i);
                SearchSpec innerSpec = spec;
                Map<String, Object> hit = renderHit(c, innerSpec, inner.sorter(), true, highlight, userQuery, mappedFieldsCache);
                if (!innerSorted) {
                    hit.remove("sort");
                } else {
                    hit.put("sort", renderSort(c, inner.sorter()));
                }
                hits.add(hit);
            }
            Map<String, Object> hitsSection = new LinkedHashMap<>();
            hitsSection.put("total", Map.of("value", (long) sorted.size(), "relation", "eq"));
            hitsSection.put("max_score", max);
            hitsSection.put("hits", hits);
            out.put(inner.name(), Map.of("hits", hitsSection));
        }
        return out;
    }

    private Map<String, Object> renderHit(QueryPhase.Candidate c, SearchSpec spec, QueryPhase.Sorter sorter, boolean scoresVisible,
                                          HighlightPhase highlight, Query userQuery,
                                          Map<Integer, Map<String, String>> mappedFieldsCache) throws IOException {
        ShardTarget shard = c.shard;
        StoredDocCodec.Decoded decoded = shard.fetch(c.doc);
        Map<String, Object> hit = new LinkedHashMap<>();
        if (spec.explain) {
            hit.put("_shard", "[" + shard.index() + "][" + shard.shardId.id() + "]");
            hit.put("_node", engine.nodeId());
        }
        hit.put("_index", shard.index());
        boolean none = spec.storedFields != null && spec.storedFields.contains("_none_");
        if (!none) {
            hit.put("_id", decoded == null ? null : decoded.id());
        }
        if (spec.version && decoded != null) {
            hit.put("_version", decoded.version());
        }
        if (spec.seqNoPrimaryTerm && decoded != null) {
            hit.put("_seq_no", decoded.seqNo());
            hit.put("_primary_term", decoded.primaryTerm());
        }
        hit.put("_score", scoresVisible && !Float.isNaN(c.score) ? (Object) c.score : null);
        Map<String, Object> source = decoded == null || decoded.source() == null ? null : SearchEngine.sourceOf(decoded.source());
        boolean returnSource = spec.fetchSource && !none && (spec.storedFields == null || spec.sourceExplicit);
        if (returnSource && source != null) {
            hit.put("_source", FieldValues.filterSource(source, spec.includes, spec.excludes));
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (!spec.fields.isEmpty() && source != null) {
            Map<String, List<Object>> flat = new LinkedHashMap<>();
            FieldValues.flattenPaths("", source, flat);
            for (SearchSpec.FieldRequest req : spec.fields) {
                for (Map.Entry<String, List<Object>> e : flat.entrySet()) {
                    boolean match = Regex.isSimpleMatchPattern(req.field()) ? Regex.simpleMatch(req.field(), e.getKey())
                        : req.field().equals(e.getKey());
                    if (match && shard.fieldType(e.getKey()) != null) {
                        QueryFactory.FieldType type = shard.fieldType(e.getKey());
                        List<Object> values = new ArrayList<>();
                        for (Object v : e.getValue()) {
                            values.add(req.format() != null && FieldValues.kind(type) == FieldValues.Kind.DATE ? String.valueOf(v) : v);
                        }
                        fields.put(e.getKey(), values);
                    }
                }
            }
        }
        if (!spec.docvalueFields.isEmpty()) {
            Map<String, String> mapped = mappedFieldsCache.computeIfAbsent(shard.ordinal, k -> mappedFields(shard));
            for (SearchSpec.FieldRequest req : spec.docvalueFields) {
                List<String> names = new ArrayList<>();
                if (Regex.isSimpleMatchPattern(req.field())) {
                    for (String name : mapped.keySet()) {
                        if (Regex.simpleMatch(req.field(), name) && FieldValues.kind(shard.fieldType(name)) != FieldValues.Kind.NONE) {
                            names.add(name);
                        }
                    }
                } else {
                    names.add(req.field());
                }
                for (String name : names) {
                    QueryFactory.FieldType type = shard.fieldType(name);
                    if (type == null) {
                        continue;
                    }
                    if (FieldValues.kind(type) == FieldValues.Kind.NONE) {
                        throw new RestApiException(400, "Text fields are not optimised for operations that require per-document "
                            + "field data like aggregations and sorting, so these operations are disabled by default. Please use a "
                            + "keyword field instead. Alternatively, set fielddata=true on [" + name + "] in order to load field data "
                            + "by uninverting the inverted index.");
                    }
                    List<Object> values = new ArrayList<>();
                    for (Object v : shard.docValues(name, c.doc)) {
                        values.add(FieldValues.format(type, v, req.format()));
                    }
                    if (!values.isEmpty()) {
                        fields.put(name, values);
                    }
                }
            }
        }
        if (spec.storedFields != null && !none) {
            Map<String, String> mapped = mappedFieldsCache.computeIfAbsent(shard.ordinal, k -> mappedFields(shard));
            for (String pattern : spec.storedFields) {
                if ("_source".equals(pattern) || "_id".equals(pattern) || "_routing".equals(pattern)) {
                    continue;
                }
                for (String name : mapped.keySet()) {
                    boolean match = Regex.isSimpleMatchPattern(pattern) ? Regex.simpleMatch(pattern, name) : pattern.equals(name);
                    if (!match) {
                        continue;
                    }
                    Map<String, Object> def = HighlightPhase.mappingDefinition(shard, name);
                    if (def == null || !"true".equals(String.valueOf(def.get("store")))) {
                        continue;
                    }
                    List<Object> values = new ArrayList<>();
                    byte[] stored = decoded == null || decoded.extraStoredFields() == null ? null : decoded.extraStoredFields().get(name);
                    List<Object> fromSource = FieldValues.fromSource(source, name);
                    if (!fromSource.isEmpty()) {
                        values.addAll(fromSource);
                    } else if (stored != null) {
                        values.add(new String(stored, StandardCharsets.UTF_8));
                    }
                    if (!values.isEmpty()) {
                        fields.put(name, values);
                    }
                }
            }
        }
        if (spec.scriptFields != null) {
            ScriptService scripts = engine.queryFactory().scriptService();
            for (Map.Entry<String, Object> e : spec.scriptFields.entrySet()) {
                Map<String, Object> def = SettingsMaps.asMap(e.getValue());
                if (def == null || def.get("script") == null) {
                    throw new RestApiException(400, "[script_fields] entry [" + e.getKey() + "] requires a [script]");
                }
                boolean ignoreFailure = "true".equals(String.valueOf(def.get("ignore_failure")));
                try {
                    Script script = Script.parse(def.get("script"));
                    Map<String, Object> vars = new LinkedHashMap<>();
                    vars.put("doc", docLookup(shard, c.doc));
                    vars.put("_source", source == null ? Map.of() : source);
                    Object value = scripts.execute(script, ScriptContext.FIELD, vars);
                    List<Object> values = new ArrayList<>();
                    if (value instanceof Collection<?> col) {
                        values.addAll(col);
                    } else {
                        values.add(value);
                    }
                    fields.put(e.getKey(), values);
                } catch (RuntimeException ex) {
                    if (!ignoreFailure) {
                        throw ex instanceof RestApiException rae ? rae : new RestApiException(400, "script_fields [" + e.getKey()
                            + "] failed: " + ex.getMessage(), ex);
                    }
                }
            }
        }
        if (!fields.isEmpty()) {
            hit.put("fields", fields);
        }
        if (highlight != null && userQuery != null) {
            Map<String, Object> hl = highlight.highlight(shard, c.doc, userQuery, spec.queryClause, source);
            if (hl != null) {
                hit.put("highlight", hl);
            }
        }
        if (spec.explicitSort) {
            hit.put("sort", renderSort(c, sorter));
        }
        if (spec.explain) {
            hit.put("_explanation", ExplainRenderer.render(shard.searcher.explain(shard.query, c.doc)));
        }
        return hit;
    }

    private static List<Object> renderSort(QueryPhase.Candidate c, QueryPhase.Sorter sorter) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < sorter.sorts.size(); i++) {
            SearchSpec.SortSpec s = sorter.sorts.get(i);
            Object v = s.type() == SearchSpec.SortType.SCORE ? (Object) c.score : c.sortValues[i];
            if (s.type() == SearchSpec.SortType.FIELD && s.format() != null && v != null) {
                v = FieldValues.format(c.shard.fieldType(s.field()), v, s.format());
            }
            out.add(v);
        }
        return out;
    }

    private static Map<String, String> mappedFields(ShardTarget shard) {
        Map<String, String> out = new LinkedHashMap<>();
        if (shard.indexService == null || shard.indexService.mapperService().documentMapper() == null) {
            return out;
        }
        Map<String, Object> mapping = SettingsMaps.asMap(shard.indexService.mapperService().documentMapper().mapping().toMapping().toJava());
        collectFields("", mapping == null ? Map.of() : mapping, out);
        return out;
    }

    private static void collectFields(String prefix, Map<String, Object> mapping, Map<String, String> out) {
        Map<String, Object> props = SettingsMaps.asMap(mapping.get("properties"));
        if (props == null) {
            return;
        }
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String name = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Map<String, Object> def = SettingsMaps.asMap(e.getValue());
            if (def == null) {
                continue;
            }
            if (def.get("type") != null) {
                out.put(name, String.valueOf(def.get("type")));
            }
            if (def.containsKey("properties")) {
                collectFields(name, def, out);
            }
            Map<String, Object> multi = SettingsMaps.asMap(def.get("fields"));
            if (multi != null) {
                for (Map.Entry<String, Object> m : multi.entrySet()) {
                    Map<String, Object> sub = SettingsMaps.asMap(m.getValue());
                    if (sub != null && sub.get("type") != null) {
                        out.put(name + "." + m.getKey(), String.valueOf(sub.get("type")));
                    }
                }
            }
        }
    }

    private static DocLookup docLookup(ShardTarget shard, int doc) {
        return new DocLookup() {
            @Override
            public ScriptDocValues<?> get(String field) {
                QueryFactory.FieldType type = shard.fieldType(field);
                if (type == null) {
                    throw new IllegalArgumentException("No field found for [" + field + "] in mapping");
                }
                List<Object> values;
                try {
                    values = shard.docValues(field, doc);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return switch (FieldValues.kind(type)) {
                    case LONG -> ScriptDocValues.longs(values.stream().mapToLong(v -> ((Number) v).longValue()).toArray());
                    case DOUBLE -> ScriptDocValues.doubles(values.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray());
                    case DATE -> ScriptDocValues.datesMillis(values.stream().mapToLong(v -> "date_nanos".equals(type.type())
                        ? ((Number) v).longValue() / 1_000_000L : ((Number) v).longValue()).toArray());
                    case BOOLEAN -> {
                        boolean[] arr = new boolean[values.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = (Boolean) values.get(i);
                        }
                        yield ScriptDocValues.booleans(arr);
                    }
                    case STRING -> ScriptDocValues.strings(values.stream().map(String::valueOf).toArray(String[]::new));
                    case NONE -> throw new IllegalArgumentException("Fielddata is disabled on [" + field + "] of type [" + type.type() + "]");
                };
            }

            @Override
            public boolean containsKey(String field) {
                return shard.fieldType(field) != null;
            }

            @Override
            public int docId() {
                return doc;
            }
        };
    }

    private void rescore(QueryPhase.ShardResult result, ShardTarget shard, List<Map<String, Object>> specs,
                         Function<Map<String, Object>, Query> parse) throws IOException {
        List<QueryPhase.Candidate> top = new ArrayList<>(result.top);
        top.sort(SCORE_SORTER);
        for (Map<String, Object> spec : specs) {
            int window = SearchEngine.intValue(spec.get("window_size"), 10);
            Map<String, Object> q = SettingsMaps.asMap(spec.get("query"));
            if (q == null || SettingsMaps.asMap(q.get("rescore_query")) == null) {
                throw new RestApiException(400, "[rescore] requires [query] with a [rescore_query]");
            }
            float queryWeight = q.get("query_weight") instanceof Number n ? n.floatValue() : 1f;
            float rescoreWeight = q.get("rescore_query_weight") instanceof Number n ? n.floatValue() : 1f;
            String mode = q.get("score_mode") == null ? "total" : String.valueOf(q.get("score_mode"));
            if (!List.of("total", "multiply", "avg", "max", "min").contains(mode)) {
                throw new RestApiException(400, "illegal score_mode [" + mode + "]");
            }
            Query rescoreQuery = parse.apply(SettingsMaps.asMap(q.get("rescore_query")));
            int n = Math.min(window, top.size());
            List<QueryPhase.Candidate> windowed = new ArrayList<>(top.subList(0, n));
            List<Integer> docs = new ArrayList<>();
            for (QueryPhase.Candidate c : windowed) {
                docs.add(c.doc);
            }
            Map<Integer, Float> scores = scoreDocs(shard, rescoreQuery, docs);
            for (QueryPhase.Candidate c : windowed) {
                Float r = scores.get(c.doc);
                float primary = queryWeight * c.score;
                if (r == null) {
                    c.score = primary;
                    continue;
                }
                float secondary = rescoreWeight * r;
                c.score = switch (mode) {
                    case "multiply" -> primary * secondary;
                    case "avg" -> (primary + secondary) / 2f;
                    case "max" -> Math.max(primary, secondary);
                    case "min" -> Math.min(primary, secondary);
                    default -> primary + secondary;
                };
            }
            windowed.sort(SCORE_SORTER);
            List<QueryPhase.Candidate> next = new ArrayList<>(windowed);
            next.addAll(top.subList(n, top.size()));
            top = next;
        }
        result.top = top;
    }

    static Map<Integer, Float> scoreDocs(ShardTarget shard, Query query, Collection<Integer> docs) throws IOException {
        Weight weight = shard.searcher.createWeight(query, ScoreMode.COMPLETE, 1f);
        Map<Integer, Float> out = new HashMap<>();
        LeafReaderContext current = null;
        Scorer scorer = null;
        for (int doc : new TreeSet<>(docs)) {
            LeafReaderContext ctx = shard.leafFor(doc);
            if (ctx == null) {
                continue;
            }
            if (ctx != current) {
                current = ctx;
                scorer = weight.scorer(ctx);
            }
            if (scorer == null) {
                continue;
            }
            int local = doc - ctx.docBase();
            int d = scorer.docID();
            if (d < local) {
                d = scorer.advance(local);
            }
            if (d == local) {
                out.put(doc, scorer.score());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private KnnRequest parseKnn(Map<String, Object> knn, int defaultK, List<ShardTarget> shards, Map<String, Object> aliasFilter,
                                Function<Map<String, Object>, Query> parse) {
        if (knn.get("field") == null) {
            throw new RestApiException(400, "[knn] requires [field]");
        }
        String field = String.valueOf(knn.get("field"));
        if (knn.get("query_vector_builder") != null) {
            throw new RestApiException(400, "[query_vector_builder] is not supported, provide [query_vector]");
        }
        if (!(knn.get("query_vector") instanceof List<?> raw) || raw.isEmpty()) {
            throw new RestApiException(400, "[knn] requires a non-empty [query_vector]");
        }
        float[] vector = new float[raw.size()];
        for (int i = 0; i < vector.length; i++) {
            if (!(raw.get(i) instanceof Number n)) {
                throw new RestApiException(400, "[query_vector] must contain only numbers");
            }
            vector[i] = n.floatValue();
        }
        int k = SearchEngine.intValue(knn.get("k"), Math.max(1, defaultK));
        if (k < 1) {
            throw new RestApiException(400, "[k] must be greater than 0");
        }
        int numCandidates = SearchEngine.intValue(knn.get("num_candidates"), Math.min(10_000, Math.max(k, (int) Math.ceil(1.5 * k))));
        if (numCandidates < k) {
            throw new RestApiException(400, "[num_candidates] cannot be less than [k]");
        }
        if (numCandidates > 10_000) {
            throw new RestApiException(400, "[num_candidates] cannot exceed [10000]");
        }
        QueryFactory.FieldType type = null;
        Integer dims = null;
        for (ShardTarget shard : shards) {
            QueryFactory.FieldType t = shard.fieldType(field);
            if (t != null) {
                type = t;
                Map<String, Object> def = HighlightPhase.mappingDefinition(shard, field);
                if (def != null && def.get("dims") instanceof Number d) {
                    dims = d.intValue();
                }
            }
        }
        if (type == null && !shards.isEmpty()) {
            throw new RestApiException(400, "failed to create query: field [" + field + "] does not exist in the mapping");
        }
        if (type != null && !"dense_vector".equals(type.type())) {
            throw new RestApiException(400, "[knn] queries are only supported on [dense_vector] fields");
        }
        if (dims != null && dims != vector.length) {
            throw new RestApiException(400, "The query vector has a different number of dimensions [" + vector.length
                + "] than the document vectors [" + dims + "].");
        }
        if (engine.queryFactory().vectorProvider() == null) {
            throw new RestApiException(400, "knn not supported for this field [" + field + "]");
        }
        Map<String, Object> filterClause = null;
        Object filter = knn.get("filter");
        if (filter instanceof List<?> list && !list.isEmpty()) {
            filterClause = Map.of("bool", Map.of("filter", list));
        } else if (filter instanceof Map<?, ?> m && !m.isEmpty()) {
            filterClause = (Map<String, Object>) m;
        }
        if (aliasFilter != null && !aliasFilter.isEmpty()) {
            filterClause = filterClause == null ? aliasFilter : Map.of("bool", Map.of("filter", List.of(filterClause, aliasFilter)));
        }
        Query filterQuery = filterClause == null ? null : parse.apply(filterClause);
        float boost = knn.get("boost") instanceof Number b ? b.floatValue() : 1f;
        return new KnnRequest(field, vector, k, numCandidates, filterQuery, boost);
    }

    private Map<Integer, Map<Integer, Float>> knnPhase(KnnRequest request, List<ShardTarget> shards) throws IOException {
        List<QueryPhase.Candidate> top = knnCandidates(request, shards);
        Map<Integer, Map<Integer, Float>> out = new HashMap<>();
        for (QueryPhase.Candidate c : top) {
            out.computeIfAbsent(c.shard.ordinal, k -> new HashMap<>()).put(c.doc, c.score * request.boost());
        }
        return out;
    }

    private List<QueryPhase.Candidate> knnCandidates(KnnRequest request, List<ShardTarget> shards) throws IOException {
        VectorSegmentAccessorProvider provider = engine.queryFactory().vectorProvider();
        List<QueryPhase.Candidate> all = new ArrayList<>();
        QueryPhase.Options none = new QueryPhase.Options();
        for (ShardTarget shard : shards) {
            Query query = new KnnVectorQuery(request.field(), request.vector(), request.k(), request.numCandidates(), request.filter(),
                provider);
            if (shard.indexBoost != 1f) {
                query = new BoostQuery(query, shard.indexBoost);
            }
            all.addAll(QueryPhase.execute(shard, query, SCORE_SORTER, request.k(), none).top);
        }
        all.sort(SCORE_SORTER);
        return new ArrayList<>(all.subList(0, Math.min(request.k(), all.size())));
    }

    @SuppressWarnings("unchecked")
    private Retrieved retrieve(Map<String, Object> retriever, List<ShardTarget> shards, SearchSpec spec, Map<String, Object> aliasFilter,
                               Function<Map<String, Object>, Query> parse, Function<String, QueryFactory.FieldType> types,
                               int window) throws IOException {
        if (retriever.size() != 1) {
            throw new RestApiException(400, "[retriever] must contain exactly one retriever type");
        }
        String type = retriever.keySet().iterator().next();
        Map<String, Object> body = SettingsMaps.asMap(retriever.get(type));
        if (body == null) {
            throw new RestApiException(400, "[" + type + "] retriever must be an object");
        }
        switch (type) {
            case "standard" -> {
                Map<String, Object> clause = SettingsMaps.asMap(body.get("query"));
                Object filter = body.get("filter");
                Map<String, Object> filterClause = filter instanceof List<?> l ? Map.of("bool", Map.of("filter", l))
                    : filter instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                Query query = parse.apply(withFilter(withFilter(clause, filterClause), aliasFilter));
                QueryPhase.Options options = new QueryPhase.Options();
                if (body.get("min_score") instanceof Number n) {
                    options.minScore = n.floatValue();
                }
                List<QueryPhase.Candidate> all = new ArrayList<>();
                long total = 0;
                for (ShardTarget shard : shards) {
                    Query q = shard.indexBoost != 1f ? new BoostQuery(query, shard.indexBoost) : query;
                    shard.query = q;
                    QueryPhase.ShardResult r = QueryPhase.execute(shard, q, SCORE_SORTER, window, options);
                    all.addAll(r.top);
                    total += r.totalHits;
                }
                all.sort(SCORE_SORTER);
                return new Retrieved(new ArrayList<>(all.subList(0, Math.min(window, all.size()))), total);
            }
            case "knn" -> {
                KnnRequest request = parseKnn(body, window, shards, aliasFilter, parse);
                List<QueryPhase.Candidate> hits = knnCandidates(request, shards);
                for (QueryPhase.Candidate c : hits) {
                    c.score *= request.boost();
                }
                return new Retrieved(hits, hits.size());
            }
            case "rrf" -> {
                if (!(body.get("retrievers") instanceof List<?> children) || children.isEmpty()) {
                    throw new RestApiException(400, "[rrf] requires a non-empty [retrievers] list");
                }
                int rankWindow = SearchEngine.intValue(body.get("rank_window_size"), window);
                int rankConstant = SearchEngine.intValue(body.get("rank_constant"), 60);
                if (rankConstant < 1) {
                    throw new RestApiException(400, "[rank_constant] must be greater than or equal to [1]");
                }
                if (rankWindow < spec.size) {
                    throw new RestApiException(400, "[rank_window_size] must be greater than or equal to [size]");
                }
                Map<Long, Integer> keys = new LinkedHashMap<>();
                List<QueryPhase.Candidate> byKey = new ArrayList<>();
                List<List<Integer>> ranked = new ArrayList<>();
                for (Object child : children) {
                    Map<String, Object> childMap = SettingsMaps.asMap(child);
                    if (childMap == null) {
                        throw new RestApiException(400, "[rrf] retrievers must be objects");
                    }
                    List<Integer> list = new ArrayList<>();
                    for (QueryPhase.Candidate c : retrieve(childMap, shards, spec, aliasFilter, parse, types, rankWindow).hits()) {
                        Integer idx = keys.get(c.shardDoc());
                        if (idx == null) {
                            idx = byKey.size();
                            keys.put(c.shardDoc(), idx);
                            byKey.add(c);
                        }
                        list.add(idx);
                    }
                    ranked.add(list);
                }
                List<QueryPhase.Candidate> fused = new ArrayList<>();
                for (RrfRetriever.RrfHit h : RrfRetriever.fuse(ranked, rankConstant, rankWindow)) {
                    QueryPhase.Candidate base = byKey.get(h.docId());
                    fused.add(new QueryPhase.Candidate(base.shard, base.doc, (float) h.score(), new Object[]{(float) h.score()}, null));
                }
                return new Retrieved(fused, keys.size());
            }
            default -> throw new RestApiException(400, "unknown retriever [" + type + "]");
        }
    }

    private Map<String, Object> profile(List<ShardTarget> shards, int topN) {
        List<Object> out = new ArrayList<>();
        for (ShardTarget shard : shards) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", "[" + engine.nodeId() + "][" + shard.index() + "][" + shard.shardId.id() + "]");
            Map<String, Object> search = new LinkedHashMap<>();
            try {
                ProfileResult result = Profiler.profile(shard.searcher, shard.query, TopScoreDocCollector.create(topN));
                List<Object> queries = new ArrayList<>();
                for (ProfileResult child : result.children()) {
                    queries.add(child.toMap());
                }
                search.put("query", queries);
                Long rewrite = result.breakdown().get("rewrite_time");
                search.put("rewrite_time", rewrite == null ? 0L : rewrite);
                search.put("collector", List.of(Map.of("name", "QueryPhaseCollector", "reason", "search_top_hits",
                    "time_in_nanos", result.timeInNanos())));
            } catch (IOException | RuntimeException e) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("type", shard.query.getClass().getSimpleName());
                q.put("description", shard.query.toString());
                q.put("time_in_nanos", 0L);
                q.put("breakdown", Map.of());
                search.put("query", List.of(q));
                search.put("rewrite_time", 0L);
                search.put("collector", List.of());
            }
            entry.put("searches", List.of(search));
            entry.put("aggregations", List.of());
            out.add(entry);
        }
        return Map.of("shards", out);
    }
}
