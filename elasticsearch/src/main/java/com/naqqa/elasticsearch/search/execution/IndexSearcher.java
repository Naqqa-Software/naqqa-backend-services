package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class IndexSearcher {

    private final List<LeafReaderContext> leaves;
    private final Similarity similarity;
    private Map<String, CollectionStatistics> dfsCollectionStats;
    private Map<Term, TermStatistics> dfsTermStats;

    public IndexSearcher(List<LeafReader> readers) {
        this(readers, new BM25Similarity());
    }

    public IndexSearcher(List<LeafReader> readers, Similarity similarity) {
        this.leaves = new ArrayList<>(readers.size());
        int docBase = 0;
        int ord = 0;
        for (LeafReader r : readers) {
            leaves.add(new LeafReaderContext(r, docBase, ord));
            docBase += r.maxDoc();
            ord++;
        }
        this.similarity = similarity;
    }

    public List<LeafReaderContext> leafContexts() {
        return leaves;
    }

    public Similarity similarity() {
        return similarity;
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
                return current;
            }
            current = next;
        }
    }

    public Weight createWeight(Query query, ScoreMode scoreMode, float boost) throws IOException {
        return rewrite(query).createWeight(this, scoreMode, boost);
    }

    public void search(Query query, Collector collector) throws IOException {
        Weight weight = createWeight(query, collector.scoreMode(), 1f);
        for (LeafReaderContext ctx : leaves) {
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

    public TopDocs search(Query query, int topN) throws IOException {
        return search(query, topN, TopScoreDocCollector.TRACK_TOTAL_HITS_ACCURATE);
    }

    public TopDocs search(Query query, int topN, int totalHitsThreshold) throws IOException {
        TopScoreDocCollector collector = TopScoreDocCollector.create(topN, totalHitsThreshold);
        search(query, collector);
        return collector.topDocs();
    }

    public TopDocs search(Query query, int topN, Sort sort) throws IOException {
        TopFieldCollector collector = TopFieldCollector.create(sort, topN);
        search(query, collector);
        return collector.topDocs();
    }

    public int count(Query query) throws IOException {
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
