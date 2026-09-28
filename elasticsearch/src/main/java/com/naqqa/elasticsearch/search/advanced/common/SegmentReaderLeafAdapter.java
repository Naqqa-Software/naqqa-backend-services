package com.naqqa.elasticsearch.search.advanced.common;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;
import com.naqqa.elasticsearch.index.engine.segment.SegmentReader;
import com.naqqa.elasticsearch.search.execution.LeafReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SegmentReaderLeafAdapter implements LeafReader, com.naqqa.elasticsearch.index.engine.segment.SegmentReaderSource {

    private final SegmentReader segmentReader;

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

    @Override
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
        return segmentReader.pointValues(field);
    }

    @Override
    public long sumTotalTermFreq(String field) {
        return segmentReader.sumTotalTermFreq(field);
    }

    @Override
    public long sumDocFreq(String field) {
        return segmentReader.sumDocFreq(field);
    }

    @Override
    public long numTerms(String field) {
        return segmentReader.numTerms(field);
    }

    @Override
    public int docCount(String field) throws IOException {
        return segmentReader.docCount(field);
    }
}
