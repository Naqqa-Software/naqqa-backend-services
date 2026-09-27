package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.similarity.CollectionStatistics;
import com.naqqa.elasticsearch.search.similarity.Explanation;
import com.naqqa.elasticsearch.search.similarity.SimScorer;
import com.naqqa.elasticsearch.search.similarity.SimWeight;
import com.naqqa.elasticsearch.search.similarity.Similarity;
import com.naqqa.elasticsearch.search.similarity.TermStatistics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class PhraseQuery extends Query {

    private final String field;
    private final List<byte[]> terms;
    private final int slop;

    public PhraseQuery(String field, List<byte[]> terms, int slop) {
        if (terms.size() < 2) {
            throw new IllegalArgumentException("PhraseQuery requires at least 2 terms");
        }
        this.field = field;
        this.terms = List.copyOf(terms);
        this.slop = slop;
    }

    public String field() {
        return field;
    }

    public List<byte[]> terms() {
        return terms;
    }

    public int slop() {
        return slop;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        TermStatistics[] termStats = new TermStatistics[terms.size()];
        for (int i = 0; i < terms.size(); i++) {
            termStats[i] = searcher.termStatistics(new Term(field, terms.get(i)));
        }
        CollectionStatistics collStats = searcher.collectionStatistics(field);
        Similarity similarity = searcher.similarity();
        boolean anyMissing = Arrays.stream(termStats).anyMatch(t -> t.docFreq() == 0);
        SimWeight simWeight = anyMissing ? null : similarity.computeWeight(collStats, termStats);
        return new PhraseWeight(this, simWeight, similarity, boost, scoreMode, slop);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PhraseQuery(").append(field).append(":\"");
        for (byte[] t : terms) {
            sb.append(new String(t, java.nio.charset.StandardCharsets.UTF_8)).append(' ');
        }
        return sb.append("\", slop=").append(slop).append(')').toString();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PhraseQuery pq)) {
            return false;
        }
        if (!field.equals(pq.field) || slop != pq.slop || terms.size() != pq.terms.size()) {
            return false;
        }
        for (int i = 0; i < terms.size(); i++) {
            if (!Arrays.equals(terms.get(i), pq.terms.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Objects.hash(field, slop);
        for (byte[] t : terms) {
            h = h * 31 + Arrays.hashCode(t);
        }
        return h;
    }

    private final class PhraseWeight extends Weight {
        private final SimWeight simWeight;
        private final Similarity similarity;
        private final float boost;
        private final ScoreMode scoreMode;
        private final int slop;

        PhraseWeight(Query query, SimWeight simWeight, Similarity similarity, float boost, ScoreMode scoreMode, int slop) {
            super(query);
            this.simWeight = simWeight;
            this.similarity = similarity;
            this.boost = boost;
            this.scoreMode = scoreMode;
            this.slop = slop;
        }

        private PostingsEnum[] openPostings(LeafReaderContext context) throws IOException {
            TermsEnum te = context.reader().terms(field);
            if (te == null) {
                return null;
            }
            var info = context.reader().fieldInfo(field);
            int flags = info != null ? info.indexOptions() : PostingsFlags.POSITIONS;
            PostingsEnum[] result = new PostingsEnum[terms.size()];
            for (int i = 0; i < terms.size(); i++) {
                if (!te.seekExact(terms.get(i))) {
                    return null;
                }
                result[i] = te.postings(flags);
            }
            return result;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            if (simWeight == null) {
                return null;
            }
            PostingsEnum[] postings = openPostings(context);
            if (postings == null) {
                return null;
            }
            NormsReader norms = context.reader().norms(field);
            SimScorer simScorer = similarity.simScorer(simWeight);
            return new PhraseScorer(this, postings, slop, simScorer, norms, boost, scoreMode.needsScores());
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            if (simWeight == null) {
                return Explanation.noMatch("a phrase term is missing from this field");
            }
            PostingsEnum[] postings = openPostings(context);
            if (postings == null) {
                return Explanation.noMatch("a phrase term is missing from this segment");
            }
            NormsReader norms = context.reader().norms(field);
            SimScorer simScorer = similarity.simScorer(simWeight);
            PhraseScorer scorer = new PhraseScorer(this, postings, slop, simScorer, norms, boost, true);
            int found = scorer.advance(doc);
            if (found != doc) {
                return Explanation.noMatch("no phrase match at doc " + doc);
            }
            float value = scorer.score();
            return Explanation.match(value, "weight(phrase in doc " + doc + "), product of:",
                Explanation.match(boost, "boost"),
                Explanation.match(scorer.currentFreq, "phraseFreq=" + scorer.currentFreq));
        }
    }

    static final class PhraseScorer extends Scorer {
        private final PostingsEnum[] postings;
        private final DocIdSetIterator conjunction;
        private final int slop;
        private final SimScorer simScorer;
        private final NormsReader norms;
        private final float boost;
        private final boolean needsScores;
        float currentFreq;

        PhraseScorer(Weight weight, PostingsEnum[] postings, int slop, SimScorer simScorer, NormsReader norms,
                     float boost, boolean needsScores) {
            super(weight);
            this.postings = postings;
            List<DocIdSetIterator> all = new ArrayList<>(postings.length);
            for (PostingsEnum pe : postings) {
                all.add(pe);
            }
            this.conjunction = ConjunctionUtil.intersect(all);
            this.slop = slop;
            this.simScorer = simScorer;
            this.norms = norms;
            this.boost = boost;
            this.needsScores = needsScores;
        }

        @Override
        public int docID() {
            return conjunction.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return doNext(conjunction.nextDoc());
        }

        @Override
        public int advance(int target) throws IOException {
            return doNext(conjunction.advance(target));
        }

        private int doNext(int doc) throws IOException {
            while (doc != NO_MORE_DOCS && !matches()) {
                doc = conjunction.nextDoc();
            }
            return doc;
        }

        boolean matches() throws IOException {
            int[][] positions = new int[postings.length][];
            for (int i = 0; i < postings.length; i++) {
                positions[i] = loadPositions(postings[i]);
            }
            if (slop == 0) {
                int matchCount = 0;
                for (int p0 : positions[0]) {
                    boolean ok = true;
                    for (int i = 1; i < positions.length; i++) {
                        if (!contains(positions[i], p0 + i)) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        matchCount++;
                    }
                }
                currentFreq = matchCount;
                return matchCount > 0;
            }
            int[] dpPrev = new int[positions[0].length];
            int[] prevPositions = positions[0];
            for (int i = 1; i < positions.length; i++) {
                int[] cur = positions[i];
                int[] dpCur = new int[cur.length];
                for (int j = 0; j < cur.length; j++) {
                    int best = Integer.MAX_VALUE;
                    for (int k = 0; k < prevPositions.length; k++) {
                        int cost = dpPrev[k] + Math.abs((cur[j] - prevPositions[k]) - 1);
                        if (cost < best) {
                            best = cost;
                        }
                    }
                    dpCur[j] = best;
                }
                dpPrev = dpCur;
                prevPositions = cur;
            }
            int minDistance = Integer.MAX_VALUE;
            for (int v : dpPrev) {
                if (v < minDistance) {
                    minDistance = v;
                }
            }
            if (minDistance <= slop) {
                currentFreq = 1.0f / (minDistance + 1);
                return true;
            }
            currentFreq = 0f;
            return false;
        }

        private static boolean contains(int[] sortedPositions, int target) {
            int lo = 0;
            int hi = sortedPositions.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (sortedPositions[mid] == target) {
                    return true;
                } else if (sortedPositions[mid] < target) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return false;
        }

        private static int[] loadPositions(PostingsEnum pe) throws IOException {
            int freq = pe.freq();
            int[] result = new int[freq];
            for (int i = 0; i < freq; i++) {
                result[i] = pe.nextPosition();
            }
            return result;
        }

        @Override
        public long cost() {
            return conjunction.cost();
        }

        @Override
        public float score() {
            if (!needsScores) {
                return boost;
            }
            long norm = norms == null ? 1 : norms.fieldLength(docID());
            return boost * simScorer.score(currentFreq, norm);
        }

        @Override
        public TwoPhaseIterator twoPhaseIterator() {
            return new TwoPhaseIterator(conjunction) {
                @Override
                public boolean matches() throws IOException {
                    return PhraseScorer.this.matches();
                }

                @Override
                public float matchCost() {
                    return 10f * postings.length;
                }
            };
        }
    }
}
