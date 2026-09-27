package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;

import java.io.IOException;
import java.util.List;

public final class DisjunctionScorers {

    private DisjunctionScorers() {
    }

    public static Scorer sum(Weight weight, List<Scorer> scorers, int minShouldMatch, boolean allowMaxScoreSkipping) throws IOException {
        if (scorers.isEmpty()) {
            throw new IllegalArgumentException("no scorers to disjoin");
        }
        if (scorers.size() == 1 && minShouldMatch <= 1) {
            return scorers.get(0);
        }
        if (allowMaxScoreSkipping && minShouldMatch <= 1) {
            return new WANDScorer(weight, scorers);
        }
        return new DisjunctionSumScorer(weight, scorers, Math.max(minShouldMatch, 1));
    }

    static final class DisjunctionSumScorer extends Scorer {
        private final Scorer[] scorers;
        private final int minShouldMatch;
        private int doc = -1;
        private float score;
        private int matchCount;
        private final long costEstimate;

        DisjunctionSumScorer(Weight weight, List<Scorer> scorers, int minShouldMatch) {
            super(weight);
            this.scorers = scorers.toArray(new Scorer[0]);
            this.minShouldMatch = minShouldMatch;
            long cost = 0;
            for (Scorer s : this.scorers) {
                cost += s.cost();
            }
            this.costEstimate = cost;
        }

        public int matchCount() {
            return matchCount;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            return doNext(doc + 1);
        }

        @Override
        public int advance(int target) throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            return doNext(target);
        }

        private int doNext(int target) throws IOException {
            while (true) {
                int candidate = NO_MORE_DOCS;
                for (Scorer s : scorers) {
                    if (s.docID() < target) {
                        s.advance(target);
                    }
                    if (s.docID() < candidate) {
                        candidate = s.docID();
                    }
                }
                if (candidate == NO_MORE_DOCS) {
                    doc = NO_MORE_DOCS;
                    return NO_MORE_DOCS;
                }
                int count = 0;
                float total = 0f;
                for (Scorer s : scorers) {
                    if (s.docID() == candidate) {
                        count++;
                        total += s.score();
                    }
                }
                if (count >= minShouldMatch) {
                    doc = candidate;
                    score = total;
                    matchCount = count;
                    return doc;
                }
                target = candidate + 1;
            }
        }

        @Override
        public float score() {
            return score;
        }

        @Override
        public long cost() {
            return costEstimate;
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            float sum = 0f;
            for (Scorer s : scorers) {
                sum += s.getMaxScore(upTo);
            }
            return sum;
        }
    }

    static final class WANDScorer extends Scorer {
        private final Scorer[] scorers;
        private final float[] maxScores;
        private int essentialStart;
        private float minCompetitiveScore;
        private int doc = -1;
        private float score;
        private final long costEstimate;
        private final float totalMaxScore;

        WANDScorer(Weight weight, List<Scorer> scorers) throws IOException {
            super(weight);
            Scorer[] arr = scorers.toArray(new Scorer[0]);
            float[] ms = new float[arr.length];
            for (int i = 0; i < arr.length; i++) {
                ms[i] = arr[i].getMaxScore(DocIdSetIterator.NO_MORE_DOCS);
            }
            for (int i = 1; i < arr.length; i++) {
                int j = i;
                while (j > 0 && ms[j - 1] > ms[j]) {
                    float tmpScore = ms[j - 1];
                    ms[j - 1] = ms[j];
                    ms[j] = tmpScore;
                    Scorer tmpScorer = arr[j - 1];
                    arr[j - 1] = arr[j];
                    arr[j] = tmpScorer;
                    j--;
                }
            }
            this.scorers = arr;
            this.maxScores = ms;
            long cost = 0;
            float total = 0f;
            for (int i = 0; i < arr.length; i++) {
                cost += arr[i].cost();
                total += ms[i];
            }
            this.costEstimate = cost;
            this.totalMaxScore = total;
            recomputePartition();
        }

        private void recomputePartition() {
            float total = totalMaxScore;
            if (total < minCompetitiveScore) {
                essentialStart = scorers.length;
                return;
            }
            float prefixSum = 0f;
            int k = 0;
            for (; k < maxScores.length; k++) {
                float next = prefixSum + maxScores[k];
                if (next >= minCompetitiveScore) {
                    break;
                }
                prefixSum = next;
            }
            essentialStart = k;
        }

        @Override
        public void setMinCompetitiveScore(float minScore) {
            this.minCompetitiveScore = minScore;
            recomputePartition();
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            return doNext(doc + 1);
        }

        @Override
        public int advance(int target) throws IOException {
            if (doc == NO_MORE_DOCS) {
                return NO_MORE_DOCS;
            }
            return doNext(target);
        }

        private int doNext(int target) throws IOException {
            while (true) {
                if (essentialStart >= scorers.length) {
                    doc = NO_MORE_DOCS;
                    return NO_MORE_DOCS;
                }
                int candidate = NO_MORE_DOCS;
                for (int i = essentialStart; i < scorers.length; i++) {
                    Scorer s = scorers[i];
                    if (s.docID() < target) {
                        s.advance(target);
                    }
                    if (s.docID() < candidate) {
                        candidate = s.docID();
                    }
                }
                if (candidate == NO_MORE_DOCS) {
                    doc = NO_MORE_DOCS;
                    return NO_MORE_DOCS;
                }
                float total = 0f;
                for (int i = essentialStart; i < scorers.length; i++) {
                    Scorer s = scorers[i];
                    if (s.docID() == candidate) {
                        total += s.score();
                    }
                }
                for (int i = 0; i < essentialStart; i++) {
                    Scorer s = scorers[i];
                    if (s.docID() < candidate) {
                        s.advance(candidate);
                    }
                    if (s.docID() == candidate) {
                        total += s.score();
                    }
                }
                if (total >= minCompetitiveScore) {
                    doc = candidate;
                    score = total;
                    return doc;
                }
                target = candidate + 1;
            }
        }

        @Override
        public float score() {
            return score;
        }

        @Override
        public long cost() {
            return costEstimate;
        }

        @Override
        public float getMaxScore(int upTo) {
            return totalMaxScore;
        }
    }
}
