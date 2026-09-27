package com.naqqa.elasticsearch.index.mapper;

import java.util.List;
import java.util.Map;

public interface RuntimeFieldScript {

    List<Object> execute(Map<String, Object> sourceAsMap);
}
