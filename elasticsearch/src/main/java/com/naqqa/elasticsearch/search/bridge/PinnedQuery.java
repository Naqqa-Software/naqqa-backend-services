package com.naqqa.elasticsearch.search.bridge;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.Query;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;
import com.naqqa.elasticsearch.search.query.Term;
import com.naqqa.elasticsearch.search.query.TermQuery;
import com.naqqa.elasticsearch.search.query.Weight;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PinnedQuery extends Query {

    public static final String ID_FIELD = "_id";

    private final List<String> pinnedIds;
    private final Query organic;

    public PinnedQuery(List<String> pinnedIds, Query organic) {
        this.pinnedIds = List.copyOf(pinnedIds);
        this.organic = Objects.requireNonNull(organic);
    }

    public List<String> pinnedIds() {
        return pinnedIds;
    }

    public Query organic() {
        return organic;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Query rewritten = organic.rewrite(searcher);
        return rewritten != organic ? new PinnedQuery(pinnedIds, rewritten) : this;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) throws IOException {
        Weight organicWeight = organic.createWeight(searcher, ScoreMode.COMPLETE, boost);
        List<Weight> idWeights = new ArrayList<>(pinnedIds.size());
        for (String id : pinnedIds) {
            Query idQuery = new TermQuery(new Term(ID_FIELD, id));
            idWeights.add(idQuery.createWeight(searcher, ScoreMode.COMPLETE_NO_SCORES, 1f));
        }
        return new PinnedWeight(this, organicWeight, idWeights, boost);
    }

    @Override
    public String toString() {
        return "PinnedQuery(ids=" + pinnedIds + ", organic=" + organic + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PinnedQuery q && pinnedIds.equals(q.pinnedIds) && organic.equals(q.organic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pinnedIds, organic);
    }

    private static final class PinnedWeight extends Weight {
        private final Weight organicWeight;
        private final List<Weight> idWeights;
        private final float boost;

        PinnedWeight(Query query, Weight organicWeight, List<Weight> idWeights, float boost) {
            super(query);
            this.organicWeight = organicWeight;
            this.idWeights = idWeights;
            this.boost = boost;
        }

        private Map<Integer, Integer> resolvePinnedRanks(LeafReaderContext context) throws IOException {
            Map<Integer, Integer> ranks = new HashMap<>();
            for (int rank = 0; rank < idWeights.size(); rank++) {
                Scorer s = idWeights.get(rank).scorer(context);
                if (s == null) {
                    continue;
                }
                int doc = s.nextDoc();
                if (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    ranks.putIfAbsent(doc, rank);
                }
            }
            return ranks;
        }

        @Override
        public Scorer scorer(LeafReaderContext context) throws IOException {
            Map<Integer, Integer> ranks = resolvePinnedRanks(context);
            Scorer organicScorer = organicWeight.scorer(context);
            if (ranks.isEmpty()) {
                return organicScorer;
            }
            int[] pinnedDocs = ranks.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
            return new PinnedScorer(this, pinnedDocs, ranks, idWeights.size(), organicScorer, boost);
        }

        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
            Map<Integer, Integer> ranks = resolvePinnedRanks(context);
            if (ranks.containsKey(doc)) {
                float score = pinnedScore(ranks.get(doc), idWeights.size(), boost);
                return Explanation.match(score, "pinned doc at rank " + ranks.get(doc));
            }
            return organicWeight.explain(context, doc);
        }
    }

    private static float pinnedScore(int rank, int total, float boost) {
        return boost * (1_000_000f + (total - rank));
    }

    static final class PinnedScorer extends Scorer {
        private final int[] pinnedDocs;
        private final Map<Integer, Integer> ranks;
        private final int totalPinned;
        private final Scorer organic;
        private final float boost;
        private int pinnedIdx = -1;
        private int doc = -1;

        PinnedScorer(Weight weight, int[] pinnedDocs, Map<Integer, Integer> ranks, int totalPinned, Scorer organic, float boost) {
            super(weight);
            this.pinnedDocs = pinnedDocs;
            this.ranks = ranks;
            this.totalPinned = totalPinned;
            this.organic = organic;
            this.boost = boost;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() throws IOException {
            return advance(doc + 1);
        }

        @Override
        public int advance(int target) throws IOException {
            int nextPinned = advancePinned(target);
            int nextOrganic = NO_MORE_DOCS;
            if (organic != null) {
                if (organic.docID() < target) {
                    nextOrganic = organic.advance(target);
                } else {
                    nextOrganic = organic.docID();
                }
            }
            doc = Math.min(nextPinned, nextOrganic);
            if (doc == NO_MORE_DOCS) {
                doc = NO_MORE_DOCS;
            }
            return doc;
        }

        private int advancePinned(int target) {
            int idx = pinnedIdx < 0 ? 0 : pinnedIdx;
            while (idx < pinnedDocs.length && pinnedDocs[idx] < target) {
                idx++;
            }
            pinnedIdx = idx;
            return idx < pinnedDocs.length ? pinnedDocs[idx] : NO_MORE_DOCS;
        }

        @Override
        public long cost() {
            return pinnedDocs.length + (organic != null ? organic.cost() : 0);
        }

        @Override
        public float score() throws IOException {
            Integer rank = ranks.get(doc);
            if (rank != null) {
                return pinnedScore(rank, totalPinned, boost);
            }
            if (organic != null && organic.docID() == doc) {
                return organic.score();
            }
            return 0f;
        }

        @Override
        public float getMaxScore(int upTo) throws IOException {
            if (pinnedDocs.length > 0) {
                return pinnedScore(0, totalPinned, boost);
            }
            return organic != null ? organic.getMaxScore(upTo) : 0f;
        }
    }
}
