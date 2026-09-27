package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.NumericUtils;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.common.util.PriorityQueue;
import com.naqqa.elasticsearch.search.query.ScoreMode;
import com.naqqa.elasticsearch.search.query.Scorer;

import java.io.IOException;
import java.util.Arrays;

public final class TopFieldCollector implements Collector {

    public static final int TRACK_TOTAL_HITS_ACCURATE = Integer.MAX_VALUE;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final Sort sort;
    private final int numHits;
    private final int totalHitsThreshold;
    private final boolean needsScore;
    private final PriorityQueue<Entry> pq;
    private long hitCount;
    private TotalHits.Relation relation = TotalHits.Relation.EQUAL_TO;

    public static TopFieldCollector create(Sort sort, int numHits) {
        return create(sort, numHits, TRACK_TOTAL_HITS_ACCURATE);
    }

    public static TopFieldCollector create(Sort sort, int numHits, int totalHitsThreshold) {
        return new TopFieldCollector(sort, numHits, totalHitsThreshold);
    }

    private TopFieldCollector(Sort sort, int numHits, int totalHitsThreshold) {
        this.sort = sort;
        this.numHits = numHits;
        this.totalHitsThreshold = totalHitsThreshold;
        this.needsScore = Arrays.stream(sort.fields()).anyMatch(f -> f.type() == SortField.Type.SCORE);
        this.pq = new PriorityQueue<>(Math.max(numHits, 1), false) {
            @Override
            protected boolean lessThan(Entry a, Entry b) {
                return compareEntries(a, b) > 0;
            }
        };
    }

    private int compareEntries(Entry a, Entry b) {
        SortField[] fields = sort.fields();
        for (int i = 0; i < fields.length; i++) {
            int cmp = compareField(fields[i], a, b, i);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.doc, b.doc);
    }

    private static int compareField(SortField f, Entry a, Entry b, int idx) {
        int cmp = switch (f.type()) {
            case DOC -> Integer.compare(a.doc, b.doc);
            case SCORE -> -Float.compare(a.score, b.score);
            case LONG -> Long.compare((Long) a.values[idx], (Long) b.values[idx]);
            case DOUBLE -> Double.compare((Double) a.values[idx], (Double) b.values[idx]);
            case STRING -> Arrays.compareUnsigned((byte[]) a.values[idx], (byte[]) b.values[idx]);
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
                if (totalHitsThreshold != TRACK_TOTAL_HITS_ACCURATE && hitCount > totalHitsThreshold) {
                    relation = TotalHits.Relation.GREATER_THAN_OR_EQUAL_TO;
                }
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
                    pq.insertWithOverflow(new Entry(context.docBase() + doc, score, values));
                }
            }
        };
    }

    public long hitCount() {
        return hitCount;
    }

    public TopDocs topDocs() {
        int size = pq.size();
        Entry[] arr = pq.drainToArrayHighestFirst(new Entry[size]);
        ScoreDoc[] docs = new ScoreDoc[arr.length];
        for (int i = 0; i < arr.length; i++) {
            docs[i] = new ScoreDoc(arr[i].doc, arr[i].score);
        }
        return new TopDocs(new TotalHits(hitCount, relation), docs);
    }

    private static final class Entry {
        final int doc;
        final float score;
        final Object[] values;

        Entry(int doc, float score, Object[] values) {
            this.doc = doc;
            this.score = score;
            this.values = values;
        }
    }
}
