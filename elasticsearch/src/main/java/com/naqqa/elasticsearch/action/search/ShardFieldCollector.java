package com.naqqa.elasticsearch.action.search;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.execution.Collector;
import com.naqqa.elasticsearch.search.execution.LeafCollector;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.Sort;
import com.naqqa.elasticsearch.search.execution.SortField;
import com.naqqa.elasticsearch.search.execution.TotalHits;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ShardFieldCollector implements Collector {

    private static final byte[] EMPTY_BYTES = new byte[0];

    record Hit(int doc, float score, Object[] values) {
    }

    private final Sort sort;
    private final int numHits;
    private final boolean needsScore;
    private final long trackTotalHitsUpTo;
    private final PriorityQueue<Hit> pq;
    private long hitCount;

    ShardFieldCollector(Sort sort, int numHits) {
        this(sort, numHits, TotalHits.TRACK_TOTAL_HITS_ACCURATE);
    }

    ShardFieldCollector(Sort sort, int numHits, long trackTotalHitsUpTo) {
        this.sort = sort;
        this.numHits = numHits;
        this.trackTotalHitsUpTo = trackTotalHitsUpTo;
        this.needsScore = Arrays.stream(sort.fields()).anyMatch(f -> f.type() == SortField.Type.SCORE);
        this.pq = new PriorityQueue<>(Math.max(numHits, 1), false) {
            @Override
            protected boolean lessThan(Hit a, Hit b) {
                return compareHits(a, b) > 0;
            }
        };
    }

    private int compareHits(Hit a, Hit b) {
        SortField[] fields = sort.fields();
        for (int i = 0; i < fields.length; i++) {
            int cmp = compareField(fields[i], a, b, i);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.doc(), b.doc());
    }

    private static int compareField(SortField f, Hit a, Hit b, int idx) {
        int cmp = switch (f.type()) {
            case DOC -> Integer.compare(a.doc(), b.doc());
            case SCORE -> -Float.compare(a.score(), b.score());
            case LONG -> Long.compare((Long) a.values()[idx], (Long) b.values()[idx]);
            case DOUBLE -> Double.compare((Double) a.values()[idx], (Double) b.values()[idx]);
            case STRING -> Arrays.compareUnsigned((byte[]) a.values()[idx], (byte[]) b.values()[idx]);
        };
        return f.reverse() ? -cmp : cmp;
    }

    @Override
    public ScoreMode scoreMode() {
        return needsScore ? ScoreMode.COMPLETE : ScoreMode.COMPLETE_NO_SCORES;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
        SortField[] fields = sort.fields();
        NumericDocValuesReader[] numericReaders = new NumericDocValuesReader[fields.length];
        SortedDocValuesReader[] stringReaders = new SortedDocValuesReader[fields.length];
        for (int i = 0; i < fields.length; i++) {
            SortField f = fields[i];
            if (f.type() == SortField.Type.LONG || f.type() == SortField.Type.DOUBLE) {
                numericReaders[i] = context.reader().numericDocValues(f.field());
            } else if (f.type() == SortField.Type.STRING) {
                stringReaders[i] = context.reader().sortedDocValues(f.field());
            }
        }
        return new LeafCollector() {
            private Scorer scorer;

            @Override
            public void setScorer(Scorer scorer) {
                this.scorer = scorer;
            }

            @Override
            public void collect(int doc) throws IOException {
                hitCount++;
                float score = needsScore && scorer != null ? scorer.score() : 0f;
                Object[] values = new Object[fields.length];
                for (int i = 0; i < fields.length; i++) {
                    values[i] = switch (fields[i].type()) {
                        case LONG -> numericReaders[i] != null && numericReaders[i].advanceExact(doc)
                            ? numericReaders[i].longValue() : 0L;
                        case DOUBLE -> numericReaders[i] != null && numericReaders[i].advanceExact(doc)
                            ? NumericUtils.sortableLongToDouble(numericReaders[i].longValue()) : 0.0;
                        case STRING -> stringReaders[i] != null && stringReaders[i].advanceExact(doc)
                            ? stringReaders[i].lookupOrd(stringReaders[i].ordValue()) : EMPTY_BYTES;
                        case DOC, SCORE -> null;
                    };
                }
                if (numHits > 0) {
                    pq.insertWithOverflow(new Hit(context.docBase() + doc, score, values));
                }
            }
        };
    }

    long hitCount() {
        return hitCount;
    }

    List<Hit> results() {
        int size = pq.size();
        Hit[] arr = pq.drainToArrayHighestFirst(new Hit[size]);
        return new ArrayList<>(Arrays.asList(arr));
    }

    TotalHits totalHits() {
        return new TotalHits(hitCount, TotalHits.relationFor(hitCount, trackTotalHitsUpTo));
    }
}
