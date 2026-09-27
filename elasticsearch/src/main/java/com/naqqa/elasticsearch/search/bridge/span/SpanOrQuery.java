package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SpanOrQuery extends SpanQuery {

    private final List<SpanQuery> clauses;

    public SpanOrQuery(List<SpanQuery> clauses) {
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException("span_or requires at least one clause");
        }
        this.clauses = List.copyOf(clauses);
    }

    public List<SpanQuery> clauses() {
        return clauses;
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
                    if (s != null) {
                        subs.add(s);
                    }
                }
                return subs.isEmpty() ? null : new OrSpans(subs);
            }
        };
    }

    @Override
    public String toString() {
        return "SpanOrQuery(" + clauses + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanOrQuery q && clauses.equals(q.clauses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clauses);
    }

    static final class OrSpans extends Spans {
        private final List<Spans> subs;
        private int doc = -1;
        private final List<Spans> active = new ArrayList<>();
        private int[][] positions = new int[0][];
        private int posIdx = -1;

        OrSpans(List<Spans> subs) {
            this.subs = subs;
        }

        @Override
        public int docID() {
            return doc;
        }

        @Override
        public int nextDoc() throws IOException {
            return advance(doc == -1 ? 0 : doc + 1);
        }

        @Override
        public int advance(int target) throws IOException {
            int best = NO_MORE_DOCS;
            for (Spans s : subs) {
                if (s.docID() < target) {
                    s.advance(target);
                }
                if (s.docID() < best) {
                    best = s.docID();
                }
            }
            doc = best;
            active.clear();
            if (doc != NO_MORE_DOCS) {
                for (Spans s : subs) {
                    if (s.docID() == doc) {
                        active.add(s);
                    }
                }
            }
            resetPositions();
            return doc;
        }

        private void resetPositions() throws IOException {
            List<int[]> all = new ArrayList<>();
            for (Spans s : active) {
                int st;
                while ((st = s.nextStartPosition()) != NO_MORE_POSITIONS) {
                    all.add(new int[] {st, s.endPosition()});
                }
            }
            all.sort((a, b) -> Integer.compare(a[0], b[0]));
            positions = all.toArray(new int[0][]);
            posIdx = -1;
        }

        @Override
        public long cost() {
            long c = 0;
            for (Spans s : subs) {
                c += s.cost();
            }
            return c;
        }

        @Override
        public int nextStartPosition() {
            posIdx++;
            return posIdx < positions.length ? positions[posIdx][0] : NO_MORE_POSITIONS;
        }

        @Override
        public int startPosition() {
            if (posIdx < 0) {
                return -1;
            }
            return posIdx < positions.length ? positions[posIdx][0] : NO_MORE_POSITIONS;
        }

        @Override
        public int endPosition() {
            if (posIdx < 0) {
                return -1;
            }
            return posIdx < positions.length ? positions[posIdx][1] : NO_MORE_POSITIONS;
        }

        @Override
        public int width() {
            return posIdx >= 0 && posIdx < positions.length ? positions[posIdx][1] - positions[posIdx][0] : 0;
        }
    }
}
