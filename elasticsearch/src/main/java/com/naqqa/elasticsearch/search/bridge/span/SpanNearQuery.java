package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.ConjunctionUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SpanNearQuery extends SpanQuery {

    private final List<SpanQuery> clauses;
    private final int slop;
    private final boolean inOrder;

    public SpanNearQuery(List<SpanQuery> clauses, int slop, boolean inOrder) {
        if (clauses.size() < 2) {
            throw new IllegalArgumentException("span_near requires at least two clauses");
        }
        this.clauses = List.copyOf(clauses);
        this.slop = slop;
        this.inOrder = inOrder;
    }

    public List<SpanQuery> clauses() {
        return clauses;
    }

    public int slop() {
        return slop;
    }

    public boolean inOrder() {
        return inOrder;
    }

    @Override
    public String field() {
        return clauses.get(0).field();
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        List<SpanWeight> weights = new ArrayList<>(clauses.size());
        for (SpanQuery q : clauses) {
            weights.add(q.createSpanWeight(searcher, boost));
        }
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                List<Spans> subs = new ArrayList<>(weights.size());
                for (SpanWeight w : weights) {
                    Spans s = w.getSpans(context);
                    if (s == null) {
                        return null;
                    }
                    subs.add(s);
                }
                return new NearSpans(subs, inOrder, slop);
            }
        };
    }

    @Override
    public String toString() {
        return "SpanNearQuery(" + clauses + ", slop=" + slop + ", inOrder=" + inOrder + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanNearQuery q && clauses.equals(q.clauses) && slop == q.slop && inOrder == q.inOrder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(clauses, slop, inOrder);
    }

    static final class NearSpans extends Spans {
        private final DocIdSetIterator conjunction;
        private final Spans[] subs;
        private final boolean inOrder;
        private final int slop;
        private int[] match;
        private int posIdx = -1;

        NearSpans(List<Spans> subs, boolean inOrder, int slop) {
            this.subs = subs.toArray(new Spans[0]);
            this.conjunction = ConjunctionUtil.intersect(subs);
            this.inOrder = inOrder;
            this.slop = slop;
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
            if (doc != NO_MORE_DOCS) {
                computeMatch();
            } else {
                match = null;
            }
            return doc;
        }

        private void computeMatch() throws IOException {
            List<List<int[]>> occ = new ArrayList<>(subs.length);
            for (Spans s : subs) {
                List<int[]> list = new ArrayList<>();
                int st;
                while ((st = s.nextStartPosition()) != NO_MORE_POSITIONS) {
                    list.add(new int[] {st, s.endPosition()});
                }
                occ.add(list);
            }
            match = NearMatcher.bestMatch(occ, inOrder, slop);
            posIdx = -1;
        }

        @Override
        public long cost() {
            return conjunction.cost();
        }

        @Override
        public int nextStartPosition() {
            posIdx++;
            if (match != null && posIdx == 0) {
                return match[0];
            }
            return NO_MORE_POSITIONS;
        }

        @Override
        public int startPosition() {
            if (posIdx < 0) {
                return -1;
            }
            return match != null && posIdx == 0 ? match[0] : NO_MORE_POSITIONS;
        }

        @Override
        public int endPosition() {
            if (posIdx < 0) {
                return -1;
            }
            return match != null && posIdx == 0 ? match[1] : NO_MORE_POSITIONS;
        }

        @Override
        public int width() {
            return match != null ? match[1] - match[0] : 0;
        }
    }
}
