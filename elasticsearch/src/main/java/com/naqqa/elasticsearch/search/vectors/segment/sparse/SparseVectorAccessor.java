package com.naqqa.elasticsearch.search.vectors.segment.sparse;

import java.util.Map;

public interface SparseVectorAccessor {

    int maxDoc();

    boolean hasFeatures(int docId);

    Map<String, Float> getFeatures(int docId);
}
