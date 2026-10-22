package com.naqqa.elasticsearch.os;

import java.util.List;
import java.util.Map;

public interface SearchDataset {

    String index();

    List<Map<String, Object>> docs();

    String id(Map<String, Object> doc);

    Object text(Map<String, Object> doc);

    default Object field(Map<String, Object> doc, String name) {
        return SearchExecutor.defaultField(doc, name);
    }
}
