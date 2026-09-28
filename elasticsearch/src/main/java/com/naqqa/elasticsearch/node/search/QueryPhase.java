package com.naqqa.elasticsearch.node.search;

import com.naqqa.elasticsearch.action.search.AggsPhase;
import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.execution.LeafCollector;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class QueryPhase {

    static final class Candidate {
        final ShardTarget shard;
        final int doc;
        float score;
        final Object[] sortValues;
        final Object collapseKey;

        Candidate(ShardTarget shard, int doc, float score, Object[] sortValues, Object collapseKey) {
            this.shard = shard;
            this.doc = doc;
            this.score = score;
            this.sortValues = sortValues;
            this.collapseKey = collapseKey;
        }

        long shardDoc() {
            return ((long) shard.ordinal << 32) | (doc & 0xFFFFFFFFL);
        }
    }

    static final class Options {
        Query postFilter;
        Float minScore;
        int terminateAfter;
        long deadlineNanos;
        Object[] searchAfter;
        Map<String, Object> aggs;
        String collapseField;
    }

    static final class ShardResult {
        final ShardTarget shard;
        List<Candidate> top = new ArrayList<>();
        Map<Object, List<Candidate>> groups;
        long totalHits;
        boolean timedOut;
        boolean terminatedEarly;
        InternalAggregations aggregations;

        ShardResult(ShardTarget shard) {
            this.shard = shard;
        }
    }

    static final class Sorter implements Comparator<Candidate> {
        final List<SearchSpec.SortSpec> sorts;

        Sorter(List<SearchSpec.SortSpec> sorts) {
            this.sorts = sorts.isEmpty() ? List.of(new SearchSpec.SortSpec(null, SearchSpec.SortType.SCORE, true, null, null, null, null))
                : sorts;
        }

        boolean byScoreOnly() {
            return sorts.size() == 1 && sorts.get(0).type() == SearchSpec.SortType.SCORE && sorts.get(0).desc();
        }

        boolean needsScores() {
            for (SearchSpec.SortSpec s : sorts) {
                if (s.type() == SearchSpec.SortType.SCORE) {
                    return true;
                }
            }
            return false;
        }

        Object[] values(ShardTarget shard, LeafReaderContext ctx, int localDoc, float score) throws IOException {
            Object[] out = new Object[sorts.size()];
            for (int i = 0; i < out.length; i++) {
                SearchSpec.SortSpec s = sorts.get(i);
                out[i] = switch (s.type()) {
                    case SCORE -> score;
                    case DOC, SHARD_DOC -> ((long) shard.ordinal << 32) | ((ctx.docBase() + localDoc) & 0xFFFFFFFFL);
                    case FIELD -> fieldValue(shard, ctx, localDoc, s);
                };
            }
            return out;
        }

        static Object fieldValue(ShardTarget shard, LeafReaderContext ctx, int localDoc, SearchSpec.SortSpec s) throws IOException {
            QueryFactory.FieldType type = shard.fieldType(s.field());
            FieldValues.Kind kind = FieldValues.kind(type);
            List<Object> values = type == null ? List.of() : FieldValues.read(ctx.reader(), s.field(), type, localDoc);
            if (values.isEmpty()) {
                return convertMissing(s, kind);
            }
            if (FieldValues.isNumeric(kind)) {
                List<Double> nums = new ArrayList<>();
                boolean integral = true;
                for (Object v : values) {
                    if (v instanceof Boolean b) {
                        nums.add(b ? 1.0 : 0.0);
                    } else {
                        Number n = (Number) v;
                        integral &= n instanceof Long;
                        nums.add(n.doubleValue());
                    }
                }
                String mode = s.mode() != null ? s.mode() : (s.desc() ? "max" : "min");
                double result = switch (mode) {
                    case "max" -> Collections.max(nums);
                    case "sum" -> nums.stream().mapToDouble(Double::doubleValue).sum();
                    case "avg" -> nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    case "median" -> {
                        List<Double> sorted = new ArrayList<>(nums);
                        Collections.sort(sorted);
                        int n = sorted.size();
                        yield n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
                    }
                    default -> Collections.min(nums);
                };
                if (values.size() == 1) {
                    Object single = values.get(0);
                    return single instanceof Boolean b ? (Object) (b ? 1L : 0L) : single;
                }
                if (integral && (mode.equals("min") || mode.equals("max") || mode.equals("sum"))) {
                    return (long) result;
                }
                return result;
            }
            List<String> strings = new ArrayList<>();
            for (Object v : values) {
                strings.add(String.valueOf(v));
            }
            String mode = s.mode() != null ? s.mode() : (s.desc() ? "max" : "min");
            return "max".equals(mode) ? Collections.max(strings) : Collections.min(strings);
        }

        static Object convertMissing(SearchSpec.SortSpec s, FieldValues.Kind kind) {
            Object missing = s.missingValue();
            if (missing == null) {
                return null;
            }
            if (FieldValues.isNumeric(kind)) {
                if (missing instanceof Number n) {
                    return n instanceof Double || n instanceof Float ? (Object) n.doubleValue() : (Object) n.longValue();
                }
                try {
                    String str = String.valueOf(missing);
                    return str.contains(".") ? (Object) Double.parseDouble(str) : (Object) Long.parseLong(str);
                } catch (NumberFormatException e) {
                    throw new RestApiException(400, "failed to parse [missing] value [" + missing + "] for sort field [" + s.field() + "]");
                }
            }
            return String.valueOf(missing);
        }

        Object[] convertAfter(List<Object> after) {
            Object[] out = new Object[sorts.size()];
            for (int i = 0; i < out.length; i++) {
                Object v = after.get(i);
                SearchSpec.SortSpec s = sorts.get(i);
                if (v == null) {
                    out[i] = null;
                    continue;
                }
                out[i] = switch (s.type()) {
                    case SCORE -> v instanceof Number n ? n.floatValue() : Float.parseFloat(String.valueOf(v));
                    case DOC, SHARD_DOC -> v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
                    case FIELD -> {
                        if (v instanceof Number n) {
                            yield n instanceof Double || n instanceof Float || n instanceof java.math.BigDecimal ? (Object) n.doubleValue()
                                : (Object) n.longValue();
                        }
                        if (v instanceof Boolean b) {
                            yield b ? 1L : 0L;
                        }
                        yield String.valueOf(v);
                    }
                };
            }
            return out;
        }

        int compareToAfter(Object[] values, float score, Object[] after) {
            for (int i = 0; i < sorts.size(); i++) {
                SearchSpec.SortSpec s = sorts.get(i);
                Object v = s.type() == SearchSpec.SortType.SCORE ? (Object) score : values[i];
                int cmp = compareValues(s, v, after[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }

        @Override
        public int compare(Candidate a, Candidate b) {
            for (int i = 0; i < sorts.size(); i++) {
                SearchSpec.SortSpec s = sorts.get(i);
                Object va = s.type() == SearchSpec.SortType.SCORE ? (Object) a.score : a.sortValues[i];
                Object vb = s.type() == SearchSpec.SortType.SCORE ? (Object) b.score : b.sortValues[i];
                int cmp = compareValues(s, va, vb);
                if (cmp != 0) {
                    return cmp;
                }
            }
            int cmp = Integer.compare(a.shard.ordinal, b.shard.ordinal);
            return cmp != 0 ? cmp : Integer.compare(a.doc, b.doc);
        }

        static int compareValues(SearchSpec.SortSpec s, Object a, Object b) {
            if (a == null || b == null) {
                if (a == null && b == null) {
                    return 0;
                }
                boolean first = s.missingFirst();
                if (a == null) {
                    return first ? -1 : 1;
                }
                return first ? 1 : -1;
            }
            int cmp = compareNonNull(a, b);
            return s.desc() ? -cmp : cmp;
        }

        static int compareNonNull(Object a, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                if (a instanceof Long la && b instanceof Long lb) {
                    return Long.compare(la, lb);
                }
                return Double.compare(na.doubleValue(), nb.doubleValue());
            }
            if (a instanceof String sa && b instanceof String sb) {
                return sa.compareTo(sb);
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    private QueryPhase() {
    }

    static ShardResult execute(ShardTarget shard, Query query, Sorter sorter, int topN, Options options) throws IOException {
        ShardResult result = new ShardResult(shard);
        Weight weight = shard.searcher.createWeight(query, ScoreMode.COMPLETE, 1f);
        Weight postFilterWeight = options.postFilter == null ? null
            : shard.searcher.createWeight(options.postFilter, ScoreMode.COMPLETE_NO_SCORES, 1f);
        AggsPhase.ShardAggregations aggs = options.aggs == null ? null
            : AggsPhase.create(options.aggs, new MultiBucketConsumer(MultiBucketConsumer.DEFAULT_MAX_BUCKETS), shard.fieldTypes);
        Comparator<Candidate> worstFirst = sorter.reversed();
        PriorityQueue<Candidate> queue = new PriorityQueue<>(Math.max(1, Math.min(topN, 1024)), worstFirst);
        Map<Object, List<Candidate>> groups = options.collapseField == null ? null : new LinkedHashMap<>();
        long count = 0;
        int ticks = 0;
        outer:
        for (LeafReaderContext ctx : shard.searcher.leafContexts()) {
            Scorer scorer = weight.scorer(ctx);
            if (scorer == null) {
                continue;
            }
            LeafCollector aggsLeaf = aggs == null ? null : aggs.collector().getLeafCollector(ctx);
            if (aggsLeaf != null) {
                aggsLeaf.setScorer(scorer);
            }
            Scorer postFilterScorer = postFilterWeight == null ? null : postFilterWeight.scorer(ctx);
            int doc;
            while ((doc = scorer.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (!ctx.reader().isLive(doc)) {
                    continue;
                }
                if (options.deadlineNanos > 0 && (++ticks & 31) == 0 && System.nanoTime() > options.deadlineNanos) {
                    result.timedOut = true;
                    break outer;
                }
                float score = scorer.score();
                if (options.minScore != null && score < options.minScore) {
                    continue;
                }
                if (aggsLeaf != null) {
                    aggsLeaf.collect(doc);
                }
                if (postFilterWeight != null) {
                    if (postFilterScorer == null) {
                        continue;
                    }
                    int pd = postFilterScorer.docID();
                    if (pd < doc) {
                        pd = postFilterScorer.advance(doc);
                    }
                    if (pd != doc) {
                        continue;
                    }
                }
                count++;
                if (topN > 0 || groups != null) {
                    Object[] values = sorter.values(shard, ctx, doc, score);
                    if (options.searchAfter == null || sorter.compareToAfter(values, score, options.searchAfter) > 0) {
                        Object key = null;
                        if (groups != null) {
                            List<Object> keyValues = FieldValues.read(ctx.reader(), options.collapseField,
                                shard.fieldType(options.collapseField), doc);
                            key = keyValues.isEmpty() ? null : keyValues.get(0);
                        }
                        Candidate candidate = new Candidate(shard, ctx.docBase() + doc, score, values, key);
                        if (groups != null) {
                            groups.computeIfAbsent(key == null ? NullKey.INSTANCE : key, k -> new ArrayList<>()).add(candidate);
                        } else {
                            queue.add(candidate);
                            if (queue.size() > topN) {
                                queue.poll();
                            }
                        }
                    }
                }
                if (options.terminateAfter > 0 && count >= options.terminateAfter) {
                    result.terminatedEarly = true;
                    break outer;
                }
            }
        }
        result.totalHits = count;
        if (groups != null) {
            for (List<Candidate> members : groups.values()) {
                members.sort(sorter);
                result.top.add(members.get(0));
            }
            result.top.sort(sorter);
            result.groups = groups;
        } else {
            List<Candidate> top = new ArrayList<>(queue);
            top.sort(sorter);
            result.top = top;
        }
        if (aggs != null) {
            result.aggregations = aggs.finish();
        }
        return result;
    }

    enum NullKey {
        INSTANCE
    }
}
