package com.naqqa.elasticsearch.bench;

import java.util.List;
import java.util.Map;

public interface Track {

    String name();

    String indexName();

    Map<String, Object> mapping(int shards);

    BulkIndexer.DocSource docSource();

    Map<String, Long> groundTruth(long docCount);

    List<QueryOp> queryOps(String indexName, long docCount, Map<String, Long> groundTruth);
}
