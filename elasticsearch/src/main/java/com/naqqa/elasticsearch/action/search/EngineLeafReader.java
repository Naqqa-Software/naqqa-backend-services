package com.naqqa.elasticsearch.action.search;

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
import com.naqqa.elasticsearch.codec.DocIdSetIterator;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

final class EngineLeafReader implements LeafReader {

    private final SegmentReader segmentReader;
    private final Map<String, long[]> fieldStatsCache = new ConcurrentHashMap<>();

    EngineLeafReader(SegmentReader segmentReader) {
        this.segmentReader = segmentReader;
    }

    SegmentReader segmentReader() {
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
        return null;
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
        return fieldStats(field)[2];
    }

    @Override
    public long sumDocFreq(String field) throws IOException {
        return fieldStats(field)[1];
    }

    @Override
    public long numTerms(String field) throws IOException {
        return fieldStats(field)[0];
    }

    @Override
    public int docCount(String field) throws IOException {
        return (int) fieldStats(field)[3];
    }

    private long[] fieldStats(String field) throws IOException {
        long[] cached = fieldStatsCache.get(field);
        if (cached != null) {
            return cached;
        }
        long numTerms = 0;
        long sumDocFreq = 0;
        long sumTotalTermFreq = 0;
        FieldInfo fi = segmentReader.fieldInfo(field);
        int flags = fi != null ? fi.indexOptions() : PostingsFlags.FREQS;
        boolean[] seenDocs = new boolean[segmentReader.maxDoc()];
        TermsEnum te = segmentReader.terms(field);
        if (te != null) {
            byte[] t;
            while ((t = te.next()) != null) {
                numTerms++;
                sumDocFreq += te.docFreq();
                sumTotalTermFreq += te.totalTermFreq();
                PostingsEnum pe = te.postings(flags);
                int doc;
                while ((doc = pe.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                    if (segmentReader.isLive(doc)) {
                        seenDocs[doc] = true;
                    }
                }
            }
        }
        long docCount = 0;
        for (boolean seen : seenDocs) {
            if (seen) {
                docCount++;
            }
        }
        long[] result = {numTerms, sumDocFreq, sumTotalTermFreq, docCount};
        fieldStatsCache.put(field, result);
        return result;
    }
}
