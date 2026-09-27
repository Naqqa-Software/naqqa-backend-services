package com.naqqa.elasticsearch.index.query;

import java.util.Map;

public interface ScoreFunctionBuilder {

    String getName();

    Map<String, Object> toMap();
}
