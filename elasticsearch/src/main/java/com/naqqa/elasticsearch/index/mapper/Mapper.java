package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

public interface Mapper {

    String name();

    String fullPath();

    String typeName();

    void toMapping(JsonObject out);
}
