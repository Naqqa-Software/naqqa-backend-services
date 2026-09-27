package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;

@FunctionalInterface
public interface TypeParser {

    Mapper parse(String name, String fullPath, JsonObject node, MappingParserContext context, int depth);
}
