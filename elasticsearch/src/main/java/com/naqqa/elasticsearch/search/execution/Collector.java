package com.naqqa.elasticsearch.search.execution;

import com.naqqa.elasticsearch.search.query.ScoreMode;

import java.io.IOException;

public interface Collector {

    LeafCollector getLeafCollector(LeafReaderContext context) throws IOException;

    ScoreMode scoreMode();
}
