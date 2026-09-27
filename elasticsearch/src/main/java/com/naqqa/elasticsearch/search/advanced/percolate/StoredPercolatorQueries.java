package com.naqqa.elasticsearch.search.advanced.percolate;

import com.naqqa.elasticsearch.common.json.JsonParser;
import com.naqqa.elasticsearch.index.mapper.PercolatorFieldMapper;
import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class StoredPercolatorQueries {

    private StoredPercolatorQueries() {
    }

    public static QueryBuilder decode(byte[] storedJson) {
        String json = new String(storedJson, StandardCharsets.UTF_8);
        Map<String, Object> map = new JsonParser(json).map();
        return QueryParser.parseQuery(map);
    }

    public static QueryBuilder decode(String storedJson) {
        return decode(storedJson.getBytes(StandardCharsets.UTF_8));
    }

    public static String fieldTypeName() {
        return PercolatorFieldMapper.TYPE;
    }
}
