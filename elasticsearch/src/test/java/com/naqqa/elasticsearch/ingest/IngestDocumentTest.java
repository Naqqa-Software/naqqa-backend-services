package com.naqqa.elasticsearch.ingest;

import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class IngestDocumentTest {

    private IngestDocument newDoc() {
        Map<String, Object> source = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("field", "value");
        source.put("a", nested);
        source.put("tags", new java.util.ArrayList<>(List.of("x", "y")));
        return new IngestDocument("my-index", "1", null, null, null, source);
    }

    @Test
    public void dottedPathAndArrayIndexAccess() {
        IngestDocument doc = newDoc();
        assertEquals("value", doc.getFieldValue("a.field", String.class));
        assertEquals("x", doc.getFieldValue("tags.0", String.class));
        assertTrue(doc.hasField("a.field"));
        assertFalse(doc.hasField("a.missing"));
    }

    @Test
    public void setAndRemoveAndAppend() {
        IngestDocument doc = newDoc();
        doc.setFieldValue("a.newField", 42);
        assertEquals(Integer.valueOf(42), doc.getFieldValue("a.newField", Integer.class));
        doc.removeField("a.newField");
        assertFalse(doc.hasField("a.newField"));
        doc.appendFieldValue("tags", "z", true);
        assertEquals(List.of("x", "y", "z"), doc.getFieldValue("tags", List.class));
    }

    @Test
    public void templateRendering() {
        IngestDocument doc = newDoc();
        assertEquals("value-suffix", doc.renderTemplate("{{a.field}}-suffix"));
        Object raw = doc.renderTemplateValue("{{tags}}");
        assertEquals(List.of("x", "y"), raw);
    }

    @Test
    public void metadataFields() {
        IngestDocument doc = newDoc();
        assertEquals("my-index", doc.getIndex());
        assertEquals("1", doc.getId());
        doc.setIndex("other-index");
        assertEquals("other-index", doc.getIndex());
        assertFalse(doc.getSource().containsKey("_index"));
    }
}
