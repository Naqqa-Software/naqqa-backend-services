package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.Scorer;

import java.io.IOException;

public interface LeafCollector {

    void setScorer(Scorer scorer) throws IOException;

    void collect(int doc) throws IOException;
}
