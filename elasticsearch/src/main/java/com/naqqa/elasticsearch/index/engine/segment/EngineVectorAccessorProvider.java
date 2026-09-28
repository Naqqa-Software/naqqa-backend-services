package com.naqqa.elasticsearch.index.engine.segment;

import com.naqqa.elasticsearch.search.execution.LeafReader;
import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.execution.TopDocs;
import com.naqqa.elasticsearch.search.vectors.Bits;
import com.naqqa.elasticsearch.search.vectors.ElementType;
import com.naqqa.elasticsearch.search.vectors.VectorSimilarity;
import com.naqqa.elasticsearch.search.vectors.query.VectorSegmentAccessorProvider;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentAccessor;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentReader;

import java.io.IOException;
import java.util.List;

public final class EngineVectorAccessorProvider implements VectorSegmentAccessorProvider {

    private final List<SegmentReader> leavesByOrd;

    public EngineVectorAccessorProvider() {
        this(null);
    }

    public EngineVectorAccessorProvider(List<SegmentReader> leavesByOrd) {
        this.leavesByOrd = leavesByOrd == null ? null : List.copyOf(leavesByOrd);
    }

    public static SegmentReader resolve(LeafReaderContext context, List<SegmentReader> leavesByOrd) {
        LeafReader reader = context.reader();
        if (reader instanceof SegmentReaderSource source) {
            return source.segmentReader();
        }
        if (leavesByOrd != null && context.ord() >= 0 && context.ord() < leavesByOrd.size()) {
            SegmentReader candidate = leavesByOrd.get(context.ord());
            if (candidate.maxDoc() == reader.maxDoc()) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public VectorSegmentAccessor get(LeafReaderContext context, String field) throws IOException {
        SegmentReader segment = resolve(context, leavesByOrd);
        if (segment == null) {
            return null;
        }
        VectorSegmentReader reader = segment.vectorReader(field);
        if (reader == null) {
            return null;
        }
        return new LiveFilteringAccessor(reader, segment);
    }

    private static final class LiveFilteringAccessor implements VectorSegmentAccessor {
        private final VectorSegmentReader delegate;
        private final SegmentReader segment;

        LiveFilteringAccessor(VectorSegmentReader delegate, SegmentReader segment) {
            this.delegate = delegate;
            this.segment = segment;
        }

        private Bits combine(Bits acceptDocs) {
            int maxDoc = segment.maxDoc();
            if (acceptDocs == null) {
                return Bits.fromPredicate(d -> d >= 0 && d < maxDoc && segment.isLive(d), maxDoc);
            }
            return Bits.fromPredicate(d -> d >= 0 && d < maxDoc && segment.isLive(d) && acceptDocs.get(d), maxDoc);
        }

        @Override
        public int maxDoc() {
            return delegate.maxDoc();
        }

        @Override
        public int dims() {
            return delegate.dims();
        }

        @Override
        public ElementType elementType() {
            return delegate.elementType();
        }

        @Override
        public VectorSimilarity similarity() {
            return delegate.similarity();
        }

        @Override
        public Bits liveDocs() {
            return combine(null);
        }

        @Override
        public boolean hasVector(int docId) {
            return delegate.hasVector(docId);
        }

        @Override
        public float[] getVector(int docId) {
            return delegate.getVector(docId);
        }

        @Override
        public byte[] getByteVector(int docId) {
            return delegate.getByteVector(docId);
        }

        @Override
        public TopDocs search(float[] queryVector, int k, int numCandidates, Bits acceptDocs, boolean forceExact) throws IOException {
            return delegate.search(queryVector, k, numCandidates, combine(acceptDocs), forceExact);
        }

        @Override
        public TopDocs searchBytes(byte[] queryVector, int k, int numCandidates, Bits acceptDocs, boolean forceExact) throws IOException {
            return delegate.searchBytes(queryVector, k, numCandidates, combine(acceptDocs), forceExact);
        }
    }
}
