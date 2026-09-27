package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SpanNotQuery extends SpanQuery {

    private final SpanQuery include;
    private final SpanQuery exclude;
    private final int pre;
    private final int post;

    public SpanNotQuery(SpanQuery include, SpanQuery exclude, int pre, int post) {
        this.include = Objects.requireNonNull(include);
        this.exclude = Objects.requireNonNull(exclude);
        this.pre = pre;
        this.post = post;
    }

    public SpanQuery include() {
        return include;
    }

    public SpanQuery exclude() {
        return exclude;
    }

    @Override
    public String field() {
        return include.field();
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        SpanWeight includeWeight = include.createSpanWeight(searcher, boost);
        SpanWeight excludeWeight = exclude.createSpanWeight(searcher, 1f);
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                Spans includeSpans = includeWeight.getSpans(context);
                if (includeSpans == null) {
                    return null;
                }
                Spans excludeSpans = excludeWeight.getSpans(context);
                return new NotSpans(includeSpans, excludeSpans, pre, post);
            }
        };
    }

    @Override
    public String toString() {
        return "SpanNotQuery(include=" + include + ", exclude=" + exclude + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanNotQuery q && include.equals(q.include) && exclude.equals(q.exclude)
            && pre == q.pre && post == q.post;
    }

    @Override
    public int hashCode() {
        return Objects.hash(include, exclude, pre, post);
    }

    static final class NotSpans extends Spans {
        private final Spans include;
        private final Spans exclude;
        private final int pre;
        private final int post;
        private int[][] positions = new int[0][];
        private int posIdx = -1;

        NotSpans(Spans include, Spans exclude, int pre, int post) {
            this.include = include;
            this.exclude = exclude;
            this.pre = pre;
            this.post = post;
        }

        @Override
        public int docID() {
            return include.docID();
        }

        @Override
        public int nextDoc() throws IOException {
            return doNext(include.nextDoc());
        }

        @Override
        public int advance(int target) throws IOException {
            return doNext(include.advance(target));
        }

        private int doNext(int doc) throws IOException {
            if (doc != NO_MORE_DOCS) {
                compute(doc);
            } else {
                positions = new int[0][];
            }
            return doc;
        }

        private void compute(int doc) throws IOException {
            List<int[]> incOcc = new ArrayList<>();
            int st;
            while ((st = include.nextStartPosition()) != NO_MORE_POSITIONS) {
                incOcc.add(new int[] {st, include.endPosition()});
            }
            List<int[]> excOcc = new ArrayList<>();
            if (exclude != null) {
                if (exclude.docID() < doc) {
                    exclude.advance(doc);
                }
                if (exclude.docID() == doc) {
                    int est;
                    while ((est = exclude.nextStartPosition()) != NO_MORE_POSITIONS) {
                        excOcc.add(new int[] {est, exclude.endPosition()});
                    }
                }
            }
            List<int[]> kept = new ArrayList<>();
            for (int[] inc : incOcc) {
                boolean excluded = false;
                for (int[] exc : excOcc) {
                    if (!(exc[1] <= inc[0] - pre || exc[0] >= inc[1] + post)) {
                        excluded = true;
                        break;
                    }
                }
                if (!excluded) {
                    kept.add(inc);
                }
            }
            positions = kept.toArray(new int[0][]);
            posIdx = -1;
        }

        @Override
        public long cost() {
            return include.cost();
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
