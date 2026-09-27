package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.common.json.JsonObject;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class SourceCopyToTest {

    private static boolean hasField(List<IndexableField> fields, String name) {
        for (IndexableField f : fields) {
            if (f.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void sourceIncludesExcludesFilterStoredSource() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of(
            "_source", Map.of("includes", List.of("user.*"), "excludes", List.of("user.secret")),
            "properties", Map.of("user", Map.of("type", "object", "properties", Map.of(
                "name", Map.of("type", "keyword"),
                "secret", Map.of("type", "keyword"))))));
        ParsedDocument doc = ms.parse("1", null, Map.of("user", Map.of("name", "alice", "secret", "hunter2")));
        JsonObject source = doc.source();
        JsonObject user = source.getObject("user");
        assertNotNull(user);
        assertEquals("alice", user.getString("name"));
        assertFalse(user.has("secret"));
    }

    @Test
    public void copyToDynamicallyCreatesTargetField() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "firstName", Map.of("type", "text", "copy_to", "fullName"),
            "lastName", Map.of("type", "text", "copy_to", "fullName"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("firstName", "Ada", "lastName", "Lovelace"));
        assertTrue(hasField(doc.rootFields(), "fullName"));
        Mapper full = ms.documentMapper().mapping().root().getMapper("fullName");
        assertEquals("text", full.typeName());
    }
}
