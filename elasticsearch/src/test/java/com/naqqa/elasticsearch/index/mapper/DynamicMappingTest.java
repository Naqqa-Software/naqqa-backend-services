package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.exception.StrictDynamicMappingException;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertNull;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class DynamicMappingTest {

    @Test
    public void dynamicTypeDetectionForStringLongDoubleAndDate() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of());
        ParsedDocument doc = ms.parse("1", null, Map.of(
            "name", "hi",
            "count", 5,
            "ratio", 1.5,
            "flag", true,
            "createdAt", "2024-01-01T00:00:00.000Z"));

        Mapper name = ms.documentMapper().mapping().root().getMapper("name");
        assertEquals("text", name.typeName());
        assertNotNull(((FieldMapper) name).getMultiField("keyword"));

        assertEquals("long", ms.documentMapper().mapping().root().getMapper("count").typeName());
        assertEquals("float", ms.documentMapper().mapping().root().getMapper("ratio").typeName());
        assertEquals("boolean", ms.documentMapper().mapping().root().getMapper("flag").typeName());
        assertEquals("date", ms.documentMapper().mapping().root().getMapper("createdAt").typeName());
        assertTrue(doc.hasDynamicMappingUpdate());
    }

    @Test
    public void dynamicFalseIgnoresUnknownFields() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("dynamic", "false", "properties", Map.of("id", Map.of("type", "keyword"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("id", "a1", "extra", "ignored-value"));
        assertNull(ms.documentMapper().mapping().root().getMapper("extra"));
        boolean hasIgnored = false;
        for (IndexableField f : doc.rootFields()) {
            if (f.name().equals("_ignored")) {
                hasIgnored = true;
            }
        }
        assertTrue(hasIgnored);
    }

    @Test
    public void dynamicStrictThrows() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("dynamic", "strict", "properties", Map.of("id", Map.of("type", "keyword"))));
        assertThrows(StrictDynamicMappingException.class, () -> ms.parse("1", null, Map.of("id", "a1", "extra", "boom")));
    }

    @Test
    public void dynamicTemplateMatchesByPathAndType() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of(
            "dynamic_templates", List.of(
                Map.of("strings_as_keywords", Map.of(
                    "match_mapping_type", "string",
                    "mapping", Map.of("type", "keyword"))))));
        ms.parse("1", null, Map.of("label", "abc"));
        Mapper label = ms.documentMapper().mapping().root().getMapper("label");
        assertEquals("keyword", label.typeName());
    }
}
