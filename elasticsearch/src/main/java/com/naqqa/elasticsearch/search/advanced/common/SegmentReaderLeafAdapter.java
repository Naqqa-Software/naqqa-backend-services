package com.naqqa.elasticsearch.search.advanced.common;

import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.postings.PostingsEnum;
import com.naqqa.elasticsearch.codec.postings.PostingsFlags;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SegmentReaderLeafAdapter implements LeafReader {

    private final SegmentReader segmentReader;
    private final Map<String, FieldStats> statsCache = new ConcurrentHashMap<>();

    public SegmentReaderLeafAdapter(SegmentReader segmentReader) {
        this.segmentReader = segmentReader;
    }

    public static List<LeafReader> wrap(List<SegmentReader> readers) {
        List<LeafReader> out = new ArrayList<>(readers.size());
        for (SegmentReader r : readers) {
            out.add(new SegmentReaderLeafAdapter(r));
        }
        return out;
    }

    public SegmentReader segmentReader() {
        return segmentReader;
    }

    @Override
    public int maxDoc() {
        return segmentReader.maxDoc();
    }

    @Override
    public int numDocs() {
        return segmentReader.numDocs();
    }

    @Override
    public FixedBitSet liveDocs() {
        FixedBitSet bits = new FixedBitSet(segmentReader.maxDoc());
        for (int i = 0; i < segmentReader.maxDoc(); i++) {
            if (segmentReader.isLive(i)) {
                bits.set(i);
            }
        }
        return bits;
    }

    @Override
    public boolean isLive(int docId) {
        return segmentReader.isLive(docId);
    }

    @Override
    public FieldInfo fieldInfo(String field) {
        return segmentReader.fieldInfo(field);
    }

    @Override
    public TermsEnum terms(String field) throws IOException {
        return segmentReader.terms(field);
    }

    @Override
    public NormsReader norms(String field) {
        return segmentReader.norms(field);
    }

    @Override
    public NumericDocValuesReader numericDocValues(String field) {
        return segmentReader.numericDocValues(field);
    }

    @Override
    public SortedDocValuesReader sortedDocValues(String field) {
        return null;
    }

    @Override
    public SortedNumericDocValuesReader sortedNumericDocValues(String field) {
        return null;
    }

    @Override
    public SortedSetDocValuesReader sortedSetDocValues(String field) {
        return segmentReader.sortedSetDocValues(field);
    }

    @Override
    public BKDReader pointValues(String field) {
        return null;
    }

    @Override
    public long sumTotalTermFreq(String field) throws IOException {
        return stats(field).sumTotalTermFreq;
    }

    @Override
    public long sumDocFreq(String field) throws IOException {
        return stats(field).sumDocFreq;
    }

    @Override
    public long numTerms(String field) throws IOException {
        return stats(field).numTerms;
    }

    @Override
    public int docCount(String field) throws IOException {
        return stats(field).docCount;
    }

    private FieldStats stats(String field) throws IOException {
        FieldStats cached = statsCache.get(field);
        if (cached != null) {
            return cached;
        }
        TermsEnum te = segmentReader.terms(field);
        int numTerms = 0;
        long sumDocFreq = 0;
        long sumTotalTermFreq = 0;
        FixedBitSet seen = new FixedBitSet(Math.max(segmentReader.maxDoc(), 1));
        if (te != null) {
            byte[] t;
            while ((t = te.next()) != null) {
                numTerms++;
                sumDocFreq += te.docFreq();
                sumTotalTermFreq += te.totalTermFreq();
                PostingsEnum postings = te.postings(PostingsFlags.DOCS_ONLY);
                int doc;
                while ((doc = postings.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    seen.set(doc);
                }
            }
        }
        FieldStats result = new FieldStats(numTerms, sumDocFreq, sumTotalTermFreq, seen.cardinality());
        statsCache.put(field, result);
        return result;
    }

    private static final class FieldStats {
        final int numTerms;
        final long sumDocFreq;
        final long sumTotalTermFreq;
        final int docCount;

        FieldStats(int numTerms, long sumDocFreq, long sumTotalTermFreq, int docCount) {
            this.numTerms = numTerms;
            this.sumDocFreq = sumDocFreq;
            this.sumTotalTermFreq = sumTotalTermFreq;
            this.docCount = docCount;
        }
    }
}
