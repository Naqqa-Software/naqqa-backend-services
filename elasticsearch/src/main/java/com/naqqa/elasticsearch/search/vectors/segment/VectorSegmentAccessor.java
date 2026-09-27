package com.naqqa.elasticsearch.search.vectors.segment;

import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;

import java.io.IOException;

public interface VectorSegmentAccessor {

    int maxDoc();

    int dims();

    ElementType elementType();

    VectorSimilarity similarity();

    Bits liveDocs();

    boolean hasVector(int docId);

    float[] getVector(int docId);

    byte[] getByteVector(int docId);

    TopDocs search(float[] queryVector, int k, int numCandidates, Bits acceptDocs, boolean forceExact) throws IOException;

    TopDocs searchBytes(byte[] queryVector, int k, int numCandidates, Bits acceptDocs, boolean forceExact) throws IOException;
}
