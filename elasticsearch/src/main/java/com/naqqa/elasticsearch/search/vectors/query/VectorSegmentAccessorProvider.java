package com.naqqa.elasticsearch.search.vectors.query;

import com.naqqa.elasticsearch.search.execution.LeafReaderContext;
import com.naqqa.elasticsearch.search.vectors.segment.VectorSegmentAccessor;

import java.io.IOException;

public interface VectorSegmentAccessorProvider {

    VectorSegmentAccessor get(LeafReaderContext context, String field) throws IOException;
}
