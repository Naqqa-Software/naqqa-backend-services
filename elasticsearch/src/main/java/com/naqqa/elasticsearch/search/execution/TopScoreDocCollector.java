package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;

import java.io.IOException;

public final class TopScoreDocCollector implements Collector {

    public static final int TRACK_TOTAL_HITS_ACCURATE = Integer.MAX_VALUE;

    private final int numHits;
    private final int totalHitsThreshold;
    private final PriorityQueue<ScoreDoc> pq;
    private long hitCount;
    private TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;

    public static TopScoreDocCollector create(int numHits) {
        return create(numHits, TRACK_TOTAL_HITS_ACCURATE);
    }

    public static TopScoreDocCollector create(int numHits, int totalHitsThreshold) {
        return new TopScoreDocCollector(numHits, totalHitsThreshold);
    }

    private TopScoreDocCollector(int numHits, int totalHitsThreshold) {
        this.numHits = numHits;
        this.totalHitsThreshold = totalHitsThreshold;
        this.pq = new PriorityQueue<>(Math.max(numHits, 1), false) {
            @Override
            protected boolean lessThan(ScoreDoc a, ScoreDoc b) {
                if (a.score == b.score) {
                    return a.doc > b.doc;
                }
                return a.score < b.score;
            }
        };
    }

    @Override
    public ScoreMode scoreMode() {
        if (totalHitsThreshold == TRACK_TOTAL_HITS_ACCURATE || numHits == 0) {
            return ScoreMode.COMPLETE;
        }
        return ScoreMode.TOP_SCORES;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) {
        return new LeafCollector() {
            private Scorer scorer;

            @Override
            public void setScorer(Scorer scorer) throws IOException {
                this.scorer = scorer;
                if (numHits > 0 && pq.size() >= numHits) {
                    scorer.setMinCompetitiveScore(pq.top().score);
                }
            }

            @Override
            public void collect(int doc) throws IOException {
                float score = scorer.score();
                hitCount++;
                if (totalHitsThreshold != TRACK_TOTAL_HITS_ACCURATE && hitCount > totalHitsThreshold) {
                    relation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
                }
                if (numHits > 0) {
                    pq.insertWithOverflow(new ScoreDoc(context.docBase() + doc, score));
                    if (pq.size() >= numHits) {
                        scorer.setMinCompetitiveScore(pq.top().score);
                    }
                }
            }
        };
    }

    public long hitCount() {
        return hitCount;
    }

    public TopDocs topDocs() {
        int size = pq.size();
        ScoreDoc[] docs = pq.drainToArrayHighestFirst(new ScoreDoc[size]);
        return new TopDocs(new TotalHits(hitCount, relation), docs);
    }
}
