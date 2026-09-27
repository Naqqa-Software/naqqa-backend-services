package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

public abstract class MetadataFieldMapper implements Mapper {

    protected final String name;

    protected MetadataFieldMapper(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String fullPath() {
        return name;
    }

    @Override
    public void toMapping(JsonObject out) {
    }
}
