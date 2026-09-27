package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class ObjectNestedTest {

    private static boolean hasField(List<IndexableField> fields, String name) {
        for (IndexableField f : fields) {
            if (f.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void objectFieldFlattensDottedPaths() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "user", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "keyword"))))));
        ParsedDocument doc = ms.parse("1", null, Map.of("user", Map.of("name", "alice")));
        assertTrue(hasField(doc.rootFields(), "user.name"));
    }

    @Test
    public void nestedFieldProducesSeparateHiddenDocumentsParentLast() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "comments", Map.of("type", "nested", "properties", Map.of(
                "author", Map.of("type", "keyword"),
                "text", Map.of("type", "text"))))));
        ParsedDocument doc = ms.parse("1", null, Map.of(
            "title", "post",
            "comments", List.of(
                Map.of("author", "bob", "text", "hi"),
                Map.of("author", "carl", "text", "yo"))));
        assertEquals(2, doc.nestedDocuments().size());
        assertTrue(hasField(doc.nestedDocuments().get(0), "comments.author"));
        assertTrue(hasField(doc.nestedDocuments().get(1), "comments.author"));
        assertTrue(hasField(doc.rootFields(), "title"));
        List<List<IndexableField>> all = doc.allDocumentsParentLast();
        assertEquals(3, all.size());
        assertTrue(hasField(all.get(all.size() - 1), "title"));
    }
}
