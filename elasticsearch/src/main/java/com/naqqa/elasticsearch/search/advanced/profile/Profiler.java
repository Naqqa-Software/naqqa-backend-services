package com.naqqa.elasticsearch.search.advanced.profile;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.Collector;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafCollector;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.BoostQuery;
import com.naqqa.elasticsearch.search.query.ConstantScoreQuery;
import com.naqqa.elasticsearch.search.query.DisjunctionMaxQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Weight;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Profiler {

    private Profiler() {
    }

    public static ProfileResult profile(IndexSearcher searcher, Query query, Collector collector) throws IOException {
        long t0 = System.nanoTime();
        Query rewritten = searcher.rewrite(query);
        long rewriteNanos = System.nanoTime() - t0;
        NodeTiming rootTiming = timeNode(searcher, rewritten, collector.scoreMode(), collector);
        ProfileResult root = buildResult(rewritten, rootTiming);
        Map<String, Long> topBreakdown = new LinkedHashMap<>();
        topBreakdown.put("rewrite_time", rewriteNanos);
        return new ProfileResult("total", "profiled search", rewriteNanos + rootTiming.totalNanos, topBreakdown, List.of(root));
    }

    private static ProfileResult buildResult(Query query, NodeTiming timing) {
        List<ProfileResult> children = new ArrayList<>();
        for (int i = 0; i < timing.childQueries.size(); i++) {
            children.add(buildResult(timing.childQueries.get(i), timing.childTimings.get(i)));
        }
        Map<String, Long> breakdown = new LinkedHashMap<>();
        breakdown.put("build_scorer", timing.buildScorerNanos);
        breakdown.put("next_doc", timing.nextDocNanos);
        breakdown.put("advance", timing.advanceNanos);
        breakdown.put("score", timing.scoreNanos);
        breakdown.put("match", 0L);
        return new ProfileResult(query.getClass().getSimpleName(), query.toString(), timing.totalNanos, breakdown, children);
    }

    private static List<Query> childrenOf(Query query) {
        if (query instanceof BooleanQuery bq) {
            List<Query> out = new ArrayList<>();
            for (BooleanQuery.BooleanClause c : bq.clauses()) {
                out.add(c.query());
            }
            return out;
        }
        if (query instanceof BoostQuery bq) {
            return List.of(bq.inner());
        }
        if (query instanceof ConstantScoreQuery csq) {
            return List.of(csq.inner());
        }
        if (query instanceof DisjunctionMaxQuery dmq) {
            return dmq.subQueries();
        }
        return List.of();
    }

    private static NodeTiming timeNode(IndexSearcher searcher, Query query, ScoreMode scoreMode, Collector rootCollector) throws IOException {
        NodeTiming timing = new NodeTiming();
        long start = System.nanoTime();
        Weight weight = query.createWeight(searcher, scoreMode, 1f);
        for (LeafReaderContext ctx : searcher.leafContexts()) {
            long bs0 = System.nanoTime();
            Scorer scorer = weight.scorer(ctx);
            timing.buildScorerNanos += System.nanoTime() - bs0;
            if (scorer == null) {
                continue;
            }
            ProfilingScorer ps = new ProfilingScorer(scorer);
            LeafCollector lc = rootCollector != null ? rootCollector.getLeafCollector(ctx) : null;
            if (lc != null) {
                lc.setScorer(ps);
            }
            int doc;
            while ((doc = ps.nextDocTimed()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (ctx.reader().isLive(doc)) {
                    if (lc != null) {
                        lc.collect(doc);
                    } else if (scoreMode.needsScores()) {
                        ps.score();
                    }
                }
            }
            timing.nextDocNanos += ps.nextDocNanos;
            timing.advanceNanos += ps.advanceNanos;
            timing.scoreNanos += ps.scoreNanos;
        }
        for (Query child : childrenOf(query)) {
            NodeTiming childTiming = timeNode(searcher, child, ScoreMode.COMPLETE, null);
            timing.childQueries.add(child);
            timing.childTimings.add(childTiming);
        }
        timing.totalNanos = System.nanoTime() - start;
        return timing;
    }

    private static final class NodeTiming {
        long totalNanos;
        long buildScorerNanos;
        long nextDocNanos;
        long advanceNanos;
        long scoreNanos;
        final List<Query> childQueries = new ArrayList<>();
        final List<NodeTiming> childTimings = new ArrayList<>();
    }

    private static final class ProfilingScorer extends Scorer {
        private final Scorer inner;
        long nextDocNanos;
        long advanceNanos;
        long scoreNanos;

        ProfilingScorer(Scorer inner) {
            super(inner.weight());
            this.inner = inner;
        }

        int nextDocTimed() throws IOException {
            long t0 = System.nanoTime();
            int doc = inner.nextDoc();
            nextDocNanos += System.nanoTime() - t0;
            return doc;
        }

        @Override
        public int docID() {
            return inner.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return nextDocTimed();
        }

        @Override
        public int advance(int target) throws IOException {
            long t0 = System.nanoTime();
            int doc = inner.advance(target);
            advanceNanos += System.nanoTime() - t0;
            return doc;
        }

        @Override
        public long cost() {
            return inner.cost();
        }

        @Override
        public float score() throws IOException {
            long t0 = System.nanoTime();
            float s = inner.score();
            scoreNanos += System.nanoTime() - t0;
            return s;
        }
    }
}
