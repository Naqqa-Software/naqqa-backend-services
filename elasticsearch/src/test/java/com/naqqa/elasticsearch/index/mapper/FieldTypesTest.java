package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class FieldTypesTest {

    private static IndexableField find(List<IndexableField> fields, String name, IndexableField.Kind kind) {
        for (IndexableField f : fields) {
            if (f.name().equals(name) && f.kind() == kind) {
                return f;
            }
        }
        return null;
    }

    @Test
    public void textAndKeywordWithMultiField() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "title", Map.of("type", "text", "fields", Map.of("raw", Map.of("type", "keyword"))))));
        ParsedDocument doc = ms.parse("1", null, Map.of("title", "Hello World"));
        IndexableField textField = find(doc.rootFields(), "title", IndexableField.Kind.INDEXED_TEXT);
        assertNotNull(textField);
        assertEquals(2, textField.terms().size());
        IndexableField rawField = find(doc.rootFields(), "title.raw", IndexableField.Kind.INDEXED_TEXT);
        assertNotNull(rawField);
        assertEquals("Hello World", rawField.terms().get(0).term());
    }

    @Test
    public void numericFieldProducesPointAndDocValue() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("age", Map.of("type", "integer"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("age", 42));
        IndexableField dv = find(doc.rootFields(), "age", IndexableField.Kind.NUMERIC_DOC_VALUES);
        IndexableField pt = find(doc.rootFields(), "age", IndexableField.Kind.POINT);
        assertNotNull(dv);
        assertNotNull(pt);
        assertEquals(42L, dv.numericValue());
    }

    @Test
    public void dateFieldParsesIsoAndEpoch() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("createdAt", Map.of("type", "date"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("createdAt", "2024-01-15T10:00:00Z"));
        IndexableField dv = find(doc.rootFields(), "createdAt", IndexableField.Kind.NUMERIC_DOC_VALUES);
        assertNotNull(dv);
        assertTrue(dv.numericValue() > 0);
    }

    @Test
    public void booleanFieldProducesDocValue() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("active", Map.of("type", "boolean"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("active", true));
        IndexableField dv = find(doc.rootFields(), "active", IndexableField.Kind.NUMERIC_DOC_VALUES);
        assertNotNull(dv);
        assertEquals(1L, dv.numericValue());
    }

    @Test
    public void ipFieldParsesAddress() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("addr", Map.of("type", "ip"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("addr", "192.168.1.1"));
        IndexableField pt = find(doc.rootFields(), "addr", IndexableField.Kind.POINT);
        assertNotNull(pt);
        assertEquals(16, pt.pointDims()[0].length);
    }

    @Test
    public void geoPointFieldParsesLatLon() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("location", Map.of("type", "geo_point"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("location", Map.of("lat", 40.7, "lon", -74.0)));
        IndexableField dv = find(doc.rootFields(), "location", IndexableField.Kind.NUMERIC_DOC_VALUES);
        assertNotNull(dv);
    }

    @Test
    public void rangeFieldParsesBounds() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("priceRange", Map.of("type", "integer_range"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("priceRange", Map.of("gte", 10, "lte", 20)));
        IndexableField pt = find(doc.rootFields(), "priceRange", IndexableField.Kind.POINT);
        assertNotNull(pt);
        assertEquals(2, pt.pointDims().length);
    }

    @Test
    public void denseVectorFieldStoresRawVector() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("embedding", Map.of("type", "dense_vector", "dims", 3, "similarity", "cosine"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("embedding", List.of(1.0, 2.0, 3.0)));
        IndexableField vec = find(doc.rootFields(), "embedding", IndexableField.Kind.VECTOR);
        assertNotNull(vec);
        assertEquals(3, vec.vectorFloats().length);
        assertEquals("cosine", vec.vectorSimilarity());
    }

    @Test
    public void completionFieldIndexesInputs() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of("suggest", Map.of("type", "completion"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("suggest", Map.of("input", List.of("foo", "bar"), "weight", 5)));
        IndexableField terms = find(doc.rootFields(), "suggest", IndexableField.Kind.INDEXED_TEXT);
        assertNotNull(terms);
        assertEquals(2, terms.terms().size());
    }
}
