package com.naqqa.elasticsearch.search.query;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.common.util.FixedBitSet;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.QueryCache;
import com.naqqa.elasticsearch.search.similarity.Explanation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class MultiTermQuery extends Query {

    static final int CONSTANT_SCORE_TERM_THRESHOLD = 64;

    protected final String field;

    protected MultiTermQuery(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }

    protected abstract TermsEnum getTermsEnum(TermsEnum termsEnum) throws IOException;

    protected boolean allowConstantScoreRewrite() {
        return true;
    }

    @Override
    public Query rewrite(IndexSearcher searcher) throws IOException {
        Set<Term> matched = new LinkedHashSet<>();
        Map<LeafReaderContext, List<byte[]>> leafTerms = new IdentityHashMap<>();
        for (LeafReaderContext ctx : searcher.leafContexts()) {
            TermsEnum base = ctx.reader().terms(field);
            if (base == null) {
                continue;
            }
            TermsEnum filtered = getTermsEnum(base);
            List<byte[]> terms = new ArrayList<>();
            byte[] t;
            while ((t = filtered.next()) != null) {
                byte[] copy = t.clone();
                terms.add(copy);
                matched.add(new Term(field, copy));
            }
            if (!terms.isEmpty()) {
                leafTerms.put(ctx, terms);
            }
        }
        if (matched.isEmpty()) {
            return new MatchNoDocsQuery();
        }
        if (matched.size() > CONSTANT_SCORE_TERM_THRESHOLD && allowConstantScoreRewrite()) {
            return new ConstantScoreQuery(new LeafBitSetQuery(this, collectDocs(leafTerms)));
        }
        BooleanQuery.Builder builder = BooleanQuery.builder();
        for (Term term : matched) {
            builder.add(new TermQuery(term), BooleanQuery.Occur.SHOULD);
        }
        builder.setMinimumShouldMatch(1);
        return builder.build();
    }

    private LeafDocs collectDocs(Map<LeafReaderContext, List<byte[]>> leafTerms) throws IOException {
        LeafDocs perLeaf = new LeafDocs();
        for (Map.Entry<LeafReaderContext, List<byte[]>> entry : leafTerms.entrySet()) {
            LeafReaderContext ctx = entry.getKey();
            int maxDoc = ctx.reader().maxDoc();
            FixedBitSet bits = new FixedBitSet(Math.max(maxDoc, 1));
            var info = ctx.reader().fieldInfo(field);
            int flags = info != null ? info.indexOptions() : PostingsFlags.FREQS;
            List<byte[]> terms = entry.getValue();
            terms.sort(java.util.Arrays::compareUnsigned);
            boolean walk = (long) terms.size() * 32 >= ctx.reader().numTerms(field);
            TermsEnum lookup = ctx.reader().terms(field);
            byte[] cur = walk ? lookup.next() : null;
            boolean any = false;
            for (byte[] term : terms) {
                if (walk) {
                    int cmp = -1;
                    while (cur != null && (cmp = java.util.Arrays.compareUnsigned(cur, term)) < 0) {
                        cur = lookup.next();
                    }
                    if (cur == null) {
                        break;
                    }
                    if (cmp != 0) {
                        continue;
                    }
                } else if (!lookup.seekExact(term)) {
                    continue;
                }
                PostingsEnum postings = lookup.postings(flags);
                int doc;
                while ((doc = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (doc < maxDoc) {
                        bits.set(doc);
                        any = true;
                    }
                }
            }
            if (any) {
                perLeaf.put(ctx, bits);
            }
        }
        return perLeaf;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
        throw new IllegalStateException(this + " must be rewritten before createWeight() is called");
    }

    private static final class LeafBitSetQuery extends Query {
        private final MultiTermQuery source;
        private final LeafDocs perLeaf;

        LeafBitSetQuery(MultiTermQuery source, LeafDocs perLeaf) {
            this.source = source;
            this.perLeaf = perLeaf;
        }

        @Override
        public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost) {
            return new Weight(this) {
                @Override
                public Scorer scorer(LeafReaderContext context) {
                    FixedBitSet bits = perLeaf.get(context);
                    return bits == null ? null : new BitSetScorer(this, bits, context.reader().maxDoc(), boost);
                }

                @Override
                public Explanation explain(LeafReaderContext context, int doc) {
                    FixedBitSet bits = perLeaf.get(context);
                    if (bits != null && doc < bits.length() && bits.get(doc)) {
                        return Explanation.match(boost, source.toString());
                    }
                    return Explanation.noMatch("no matching term for " + source);
                }

                @Override
                public boolean isCacheable(LeafReaderContext context) {
                    return false;
                }
            };
        }

        @Override
        public String toString() {
            return "LeafBitSet(" + source + ")";
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private static final class LeafDocs {
        private final Map<Object, FixedBitSet> bySegment = new IdentityHashMap<>();
        private final Map<Integer, FixedBitSet> byOrd = new HashMap<>();

        void put(LeafReaderContext ctx, FixedBitSet bits) {
            bySegment.put(QueryCache.segmentKey(ctx.reader()), bits);
            byOrd.put(ctx.ord(), bits);
        }

        FixedBitSet get(LeafReaderContext ctx) {
            FixedBitSet bits = bySegment.get(QueryCache.segmentKey(ctx.reader()));
            return bits != null ? bits : byOrd.get(ctx.ord());
        }
    }

    private static final class BitSetScorer extends Scorer {
        private final FixedBitSet bits;
        private final int maxDoc;
        private final float score;
        private int doc = -1;

        BitSetScorer(Weight weight, FixedBitSet bits, int maxDoc, float score) {
            super(weight);
            this.bits = bits;
            this.maxDoc = Math.min(maxDoc, bits.length());
            this.score = score;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() {
            return advance(doc + 1);
        }

        @Override
        public int advance(int target) {
            if (target >= maxDoc) {
                return doc = NO_MORE_DOCS;
            }
            int next = bits.nextSetBit(target);
            return doc = (next < 0 || next >= maxDoc ? NO_MORE_DOCS : next);
        }

        @Override
        public long cost() {
            return maxDoc;
        }

        @Override
        public float score() {
            return score;
        }

        @Override
        public float getMaxScore(int upTo) {
            return score;
        }
    }
}
