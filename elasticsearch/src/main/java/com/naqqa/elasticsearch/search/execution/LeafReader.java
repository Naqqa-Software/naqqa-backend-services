package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.codec.docvalues.NumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedNumericDocValuesReader;
import com.naqqa.elasticsearch.codec.docvalues.SortedSetDocValuesReader;
import com.naqqa.elasticsearch.codec.fieldinfos.FieldInfo;
import com.naqqa.elasticsearch.codec.livedocs.FixedBitSet;
import com.naqqa.elasticsearch.codec.norms.NormsReader;
import com.naqqa.elasticsearch.codec.points.BKDReader;
import com.naqqa.elasticsearch.codec.terms.TermsEnum;

import java.io.IOException;

public interface LeafReader {

    int maxDoc();

    int numDocs();

    FixedBitSet liveDocs();

    default boolean isLive(int docId) {
        FixedBitSet live = liveDocs();
        return live == null || live.get(docId);
    }

    FieldInfo fieldInfo(String field);

    TermsEnum terms(String field) throws IOException;

    NormsReader norms(String field) throws IOException;

    NumericDocValuesReader numericDocValues(String field) throws IOException;

    SortedDocValuesReader sortedDocValues(String field) throws IOException;

    SortedNumericDocValuesReader sortedNumericDocValues(String field) throws IOException;

    SortedSetDocValuesReader sortedSetDocValues(String field) throws IOException;

    BKDReader pointValues(String field) throws IOException;

    long sumTotalTermFreq(String field) throws IOException;

    long sumDocFreq(String field) throws IOException;

    long numTerms(String field) throws IOException;

    int docCount(String field) throws IOException;
}
