package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;

public final class MappingMergeTest {

    @Test
    public void mergeAddsNewFieldsAndKeepsExisting() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "keyword"))));
        ms.putMapping(Map.of("properties", Map.of("b", Map.of("type", "integer"))));
        assertNotNull(ms.documentMapper().mapping().root().getMapper("a"));
        assertNotNull(ms.documentMapper().mapping().root().getMapper("b"));
    }

    @Test
    public void mergeRejectsTypeChange() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "keyword"))));
        assertThrows(IllegalArgumentException.class,
            () -> ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "integer")))));
    }

    @Test
    public void mergeRejectsIndexParamChange() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "keyword", "index", true))));
        assertThrows(IllegalArgumentException.class,
            () -> ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "keyword", "index", false)))));
    }

    @Test
    public void mergeAllowsIgnoreAboveUpdate() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "keyword", "ignore_above", 100))));
        ms.putMapping(Map.of("properties", Map.of("a", Map.of("type", "keyword", "ignore_above", 200))));
        assertNotNull(ms.documentMapper().mapping().root().getMapper("a"));
    }

    @Test
    public void totalFieldsLimitExceeded() {
        Settings settings = Settings.builder().put("index.mapping.total_fields.limit", 2).build();
        MapperService ms = MapperServiceTestSupport.newMapperService(settings);
        assertThrows(IllegalArgumentException.class,
            () -> ms.putMapping(Map.of("properties", Map.of(
                "a", Map.of("type", "keyword"),
                "b", Map.of("type", "keyword"),
                "c", Map.of("type", "keyword")))));
    }

    @Test
    public void mappingDepthLimitExceeded() {
        Settings settings = Settings.builder().put("index.mapping.depth.limit", 2).build();
        MapperService ms = MapperServiceTestSupport.newMapperService(settings);
        assertThrows(IllegalArgumentException.class,
            () -> ms.putMapping(Map.of("properties", Map.of(
                "a", Map.of("type", "object", "properties", Map.of(
                    "b", Map.of("type", "object", "properties", Map.of(
                        "c", Map.of("type", "keyword")))))))));
    }
}
