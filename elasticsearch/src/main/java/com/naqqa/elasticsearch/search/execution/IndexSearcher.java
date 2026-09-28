package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.query.BooleanQuery;
import com.naqqa.elasticsearch.search.query.ConstantScoreQuery;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.BM25Similarity;
import com.naqqa.elasticsearch.search.similarity.CollectionStatistics;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.search.similarity.Similarity;
import com.naqqa.elasticsearch.search.similarity.TermStatistics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class IndexSearcher {

    public static final int DEFAULT_MAX_DOCS_PER_SLICE = 250_000;
    public static final int DEFAULT_MAX_SEGMENTS_PER_SLICE = 5;

    private final List<LeafReaderContext> leaves;
    private final Similarity similarity;
    private final Executor executor;
    private volatile List<LeafSlice> slices;
    private Map<String, CollectionStatistics> dfsCollectionStats;
    private Map<Term, TermStatistics> dfsTermStats;

    public IndexSearcher(List<LeafReader> readers) {
        this(readers, new BM25Similarity(), null);
    }

    public IndexSearcher(List<LeafReader> readers, Similarity similarity) {
        this(readers, similarity, null);
    }

    public IndexSearcher(List<LeafReader> readers, Executor executor) {
        this(readers, new BM25Similarity(), executor);
    }

    public IndexSearcher(List<LeafReader> readers, Similarity similarity, Executor executor) {
        this.leaves = buildLeaves(readers);
        this.similarity = similarity;
        this.executor = executor;
    }

    private IndexSearcher(List<LeafReaderContext> leaves, Similarity similarity, Executor executor, boolean directLeaves) {
        this.leaves = List.copyOf(leaves);
        this.similarity = similarity;
        this.executor = executor;
    }

    public static IndexSearcher fromLeaves(List<LeafReaderContext> leaves) {
        return fromLeaves(leaves, new BM25Similarity(), null);
    }

    public static IndexSearcher fromLeaves(List<LeafReaderContext> leaves, Similarity similarity) {
        return fromLeaves(leaves, similarity, null);
    }

    public static IndexSearcher fromLeaves(List<LeafReaderContext> leaves, Executor executor) {
        return fromLeaves(leaves, new BM25Similarity(), executor);
    }

    public static IndexSearcher fromLeaves(List<LeafReaderContext> leaves, Similarity similarity, Executor executor) {
        return new IndexSearcher(leaves, similarity, executor, true);
    }

    private static List<LeafReaderContext> buildLeaves(List<LeafReader> readers) {
        List<LeafReaderContext> result = new ArrayList<>(readers.size());
        int docBase = 0;
        int ord = 0;
        for (LeafReader r : readers) {
            result.add(new LeafReaderContext(r, docBase, ord));
            docBase += r.maxDoc();
            ord++;
        }
        return result;
    }

    public static Executor newVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    public List<LeafReaderContext> leafContexts() {
        return leaves;
    }

    public Similarity similarity() {
        return similarity;
    }

    public Executor executor() {
        return executor;
    }

    public List<LeafSlice> slices() {
        List<LeafSlice> result = slices;
        if (result == null) {
            result = computeSlices(leaves, DEFAULT_MAX_DOCS_PER_SLICE, DEFAULT_MAX_SEGMENTS_PER_SLICE);
            slices = result;
        }
        return result;
    }

    public static List<LeafSlice> computeSlices(List<LeafReaderContext> leaves, int maxDocsPerSlice, int maxSegmentsPerSlice) {
        List<LeafReaderContext> sorted = new ArrayList<>(leaves);
        sorted.sort((a, b) -> Integer.compare(b.reader().maxDoc(), a.reader().maxDoc()));
        List<LeafSlice> result = new ArrayList<>();
        List<LeafReaderContext> group = new ArrayList<>();
        long docSum = 0;
        for (LeafReaderContext ctx : sorted) {
            int maxDoc = ctx.reader().maxDoc();
            if (!group.isEmpty() && docSum + maxDoc > maxDocsPerSlice) {
                result.add(new LeafSlice(group));
                group = new ArrayList<>();
                docSum = 0;
            }
            group.add(ctx);
            docSum += maxDoc;
            if (group.size() >= maxSegmentsPerSlice) {
                result.add(new LeafSlice(group));
                group = new ArrayList<>();
                docSum = 0;
            }
        }
        if (!group.isEmpty()) {
            result.add(new LeafSlice(group));
        }
        return result;
    }

    public void useDfsStatistics(Map<String, CollectionStatistics> collectionStats, Map<Term, TermStatistics> termStats) {
        this.dfsCollectionStats = collectionStats;
        this.dfsTermStats = termStats;
    }

    public Query rewrite(Query query) throws IOException {
        Query current = query;
        while (true) {
            Query next = current.rewrite(this);
            if (next == current) {
                return applyFilterCaching(current);
            }
            current = next;
        }
    }

    private static Query applyFilterCaching(Query query) {
        if (query instanceof BooleanQuery bq) {
            List<BooleanQuery.BooleanClause> newClauses = new ArrayList<>(bq.clauses().size());
            boolean changed = false;
            for (BooleanQuery.BooleanClause clause : bq.clauses()) {
                Query rewritten = applyFilterCaching(clause.query());
                if (clause.occur() == BooleanQuery.Occur.FILTER || clause.occur() == BooleanQuery.Occur.MUST_NOT) {
                    if (!(rewritten instanceof CachingQuery)) {
                        rewritten = new CachingQuery(rewritten);
                    }
                }
                if (rewritten != clause.query()) {
                    changed = true;
                }
                newClauses.add(new BooleanQuery.BooleanClause(rewritten, clause.occur()));
            }
            if (!changed) {
                return bq;
            }
            BooleanQuery.Builder builder = BooleanQuery.builder().setMinimumShouldMatch(bq.minimumShouldMatch());
            for (BooleanQuery.BooleanClause clause : newClauses) {
                builder.add(clause.query(), clause.occur());
            }
            return builder.build();
        }
        if (query instanceof ConstantScoreQuery csq) {
            Query rewrittenInner = applyFilterCaching(csq.inner());
            Query wrapped = rewrittenInner instanceof CachingQuery ? rewrittenInner : new CachingQuery(rewrittenInner);
            return wrapped == csq.inner() ? csq : new ConstantScoreQuery(wrapped);
        }
        return query;
    }

    public Weight createWeight(Query query, ScoreMode scoreMode, float boost) throws IOException {
        return rewrite(query).createWeight(this, scoreMode, boost);
    }

    public void search(Query query, Collector collector) throws IOException {
        Weight weight = createWeight(query, collector.scoreMode(), 1f);
        searchLeaves(leaves, weight, collector);
    }

    private void searchLeaves(List<LeafReaderContext> leavesSubset, Weight weight, Collector collector) throws IOException {
        for (LeafReaderContext ctx : leavesSubset) {
            Scorer scorer = weight.scorer(ctx);
            if (scorer == null) {
                continue;
            }
            LeafCollector leafCollector = collector.getLeafCollector(ctx);
            leafCollector.setScorer(scorer);
            int doc;
            while ((doc = scorer.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                if (ctx.reader().isLive(doc)) {
                    leafCollector.collect(doc);
                }
            }
        }
    }

    public <C extends Collector, T> T search(Query query, CollectorManager<C, T> collectorManager) throws IOException {
        List<LeafSlice> theSlices = executor != null ? slices() : List.of();
        if (executor == null || theSlices.size() <= 1) {
            C collector = collectorManager.newCollector();
            search(query, collector);
            return collectorManager.reduce(List.of(collector));
        }
        C firstCollector = collectorManager.newCollector();
        Weight weight = createWeight(query, firstCollector.scoreMode(), 1f);
        List<C> collectors = new ArrayList<>(theSlices.size());
        collectors.add(firstCollector);
        for (int i = 1; i < theSlices.size(); i++) {
            collectors.add(collectorManager.newCollector());
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(theSlices.size());
        for (int i = 0; i < theSlices.size(); i++) {
            LeafSlice slice = theSlices.get(i);
            C collector = collectors.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    searchLeaves(slice.leaves(), weight, collector);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, executor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UncheckedIOException uioe) {
                throw uioe.getCause();
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause);
        }
        return collectorManager.reduce(collectors);
    }

    public TopDocs search(Query query, int topN) throws IOException {
        return search(query, topN, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
    }

    public TopDocs search(Query query, int topN, int totalHitsThreshold) throws IOException {
        if (executor != null) {
            return search(query, new TopScoreDocCollectorManager(topN, totalHitsThreshold));
        }
        TopScoreDocCollector collector = TopScoreDocCollector.create(topN, totalHitsThreshold);
        search(query, collector);
        return collector.topDocs();
    }

    public TopDocs search(Query query, int topN, Sort sort) throws IOException {
        if (executor != null) {
            return search(query, new TopFieldCollectorManager(sort, topN));
        }
        TopFieldCollector collector = TopFieldCollector.create(sort, topN);
        search(query, collector);
        return collector.topDocs();
    }

    public int count(Query query) throws IOException {
        if (executor != null) {
            TopDocs topDocs = search(query, new TopScoreDocCollectorManager(0, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE));
            return (int) topDocs.totalHits().value();
        }
        TopScoreDocCollector collector = TopScoreDocCollector.create(0, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
        search(query, collector);
        return (int) collector.hitCount();
    }

    public Explanation explain(Query query, int doc) throws IOException {
        Weight weight = createWeight(query, ScoreMode.COMPLETE, 1f);
        for (LeafReaderContext ctx : leaves) {
            int localDoc = doc - ctx.docBase();
            if (localDoc >= 0 && localDoc < ctx.reader().maxDoc()) {
                return weight.explain(ctx, localDoc);
            }
        }
        throw new IllegalArgumentException("doc " + doc + " is out of range");
    }

    public CollectionStatistics collectionStatistics(String field) throws IOException {
        if (dfsCollectionStats != null && dfsCollectionStats.containsKey(field)) {
            return dfsCollectionStats.get(field);
        }
        long maxDoc = 0;
        long docCount = 0;
        long sumDocFreq = 0;
        long sumTotalTermFreq = 0;
        for (LeafReaderContext ctx : leaves) {
            maxDoc += ctx.reader().maxDoc();
            docCount += ctx.reader().docCount(field);
            sumDocFreq += ctx.reader().sumDocFreq(field);
            sumTotalTermFreq += ctx.reader().sumTotalTermFreq(field);
        }
        return new CollectionStatistics(field, maxDoc, docCount, sumDocFreq, sumTotalTermFreq);
    }

    public TermStatistics termStatistics(Term term) throws IOException {
        if (dfsTermStats != null && dfsTermStats.containsKey(term)) {
            return dfsTermStats.get(term);
        }
        long docFreq = 0;
        long totalTermFreq = 0;
        for (LeafReaderContext ctx : leaves) {
            TermsEnum te = ctx.reader().terms(term.field());
            if (te != null && te.seekExact(term.bytes())) {
                docFreq += te.docFreq();
                totalTermFreq += te.totalTermFreq();
            }
        }
        return new TermStatistics(term.bytes(), docFreq, totalTermFreq);
    }

    public int docFreq(Term term) throws IOException {
        return (int) termStatistics(term).docFreq();
    }

    public long totalTermFreq(Term term) throws IOException {
        return termStatistics(term).totalTermFreq();
    }
}
