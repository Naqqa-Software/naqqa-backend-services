package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.vectors.segment.sparse.SparseVectorAccessor;

import java.io.IOException;

public interface SparseVectorSegmentAccessorProvider {

    SparseVectorAccessor get(LeafReaderContext context, String field) throws IOException;
}
