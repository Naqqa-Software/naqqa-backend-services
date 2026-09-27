package com.naqqa.elasticsearch.search.bridge.span;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.search.execution.IndexSearcher;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.query.ConjunctionUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SpanContainingQuery extends SpanQuery {

    private final SpanQuery little;
    private final SpanQuery big;

    public SpanContainingQuery(SpanQuery little, SpanQuery big) {
        this.little = Objects.requireNonNull(little);
        this.big = Objects.requireNonNull(big);
    }

    public SpanQuery little() {
        return little;
    }

    public SpanQuery big() {
        return big;
    }

    @Override
    public String field() {
        return big.field();
    }

    @Override
    public SpanWeight createSpanWeight(IndexSearcher searcher, float boost) throws IOException {
        SpanWeight littleWeight = little.createSpanWeight(searcher, 1f);
        SpanWeight bigWeight = big.createSpanWeight(searcher, boost);
        return new SpanWeight(this, boost) {
            @Override
            public Spans getSpans(LeafReaderContext context) throws IOException {
                Spans littleSpans = littleWeight.getSpans(context);
                Spans bigSpans = bigWeight.getSpans(context);
                if (littleSpans == null || bigSpans == null) {
                    return null;
                }
                return new ContainWithinSpans(littleSpans, bigSpans, true);
            }
        };
    }

    @Override
    public String toString() {
        return "SpanContainingQuery(little=" + little + ", big=" + big + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpanContainingQuery q && little.equals(q.little) && big.equals(q.big);
    }

    @Override
    public int hashCode() {
        return Objects.hash(little, big);
    }

    static final class ContainWithinSpans extends Spans {
        private final Spans little;
        private final Spans big;
        private final boolean reportBig;
        private final DocIdSetIterator conjunction;
        private int[][] positions = new int[0][];
        private int posIdx = -1;

        ContainWithinSpans(Spans little, Spans big, boolean reportBig) {
            this.little = little;
            this.big = big;
            this.reportBig = reportBig;
            this.conjunction = ConjunctionUtil.intersect(List.of(little, big));
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
                compute();
            } else {
                positions = new int[0][];
            }
            return doc;
        }

        private void compute() throws IOException {
            List<int[]> littleOcc = drain(little);
            List<int[]> bigOcc = drain(big);
            List<int[]> kept = new ArrayList<>();
            if (reportBig) {
                for (int[] b : bigOcc) {
                    for (int[] l : littleOcc) {
                        if (l[0] >= b[0] && l[1] <= b[1]) {
                            kept.add(b);
                            break;
                        }
                    }
                }
            } else {
                for (int[] l : littleOcc) {
                    for (int[] b : bigOcc) {
                        if (l[0] >= b[0] && l[1] <= b[1]) {
                            kept.add(l);
                            break;
                        }
                    }
                }
            }
            positions = kept.toArray(new int[0][]);
            posIdx = -1;
        }

        private static List<int[]> drain(Spans spans) throws IOException {
            List<int[]> out = new ArrayList<>();
            int st;
            while ((st = spans.nextStartPosition()) != NO_MORE_POSITIONS) {
                out.add(new int[] {st, spans.endPosition()});
            }
            return out;
        }

        @Override
        public long cost() {
            return conjunction.cost();
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
