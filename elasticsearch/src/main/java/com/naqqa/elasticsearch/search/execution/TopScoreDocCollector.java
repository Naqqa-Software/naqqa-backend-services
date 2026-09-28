package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;

import java.io.IOException;

public final class TopScoreDocCollector implements Collector {

    public static final int TRACK_TOTAL_HITS_ACCURATE = Integer.MAX_VALUE;

    private final int numHits;
    private final int totalHitsThreshold;
    private final MaxScoreAccumulator minScoreAcc;
    private final PriorityQueue<ScoreDoc> pq;
    private long hitCount;
    private TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;

    public static TopScoreDocCollector create(int numHits) {
        return create(numHits, TRACK_TOTAL_HITS_ACCURATE);
    }

    public static TopScoreDocCollector create(int numHits, int totalHitsThreshold) {
        return new TopScoreDocCollector(numHits, totalHitsThreshold, null);
    }

    static TopScoreDocCollector createShared(int numHits, int totalHitsThreshold, MaxScoreAccumulator minScoreAcc) {
        return new TopScoreDocCollector(numHits, totalHitsThreshold, minScoreAcc);
    }

    private TopScoreDocCollector(int numHits, int totalHitsThreshold, MaxScoreAccumulator minScoreAcc) {
        this.numHits = numHits;
        this.totalHitsThreshold = totalHitsThreshold;
        this.minScoreAcc = minScoreAcc;
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
                float bound = localMinScore();
                if (minScoreAcc != null) {
                    bound = Math.max(bound, minScoreAcc.rawMaxScore());
                }
                if (numHits > 0 && bound != Float.NEGATIVE_INFINITY) {
                    scorer.setMinCompetitiveScore(bound);
                }
            }

            private float localMinScore() {
                return (numHits > 0 && pq.size() >= numHits) ? pq.top().score : Float.NEGATIVE_INFINITY;
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
                        float bound = pq.top().score;
                        if (minScoreAcc != null) {
                            minScoreAcc.accumulate(context.docBase() + doc, bound);
                            bound = Math.max(bound, minScoreAcc.rawMaxScore());
                        }
                        scorer.setMinCompetitiveScore(bound);
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
