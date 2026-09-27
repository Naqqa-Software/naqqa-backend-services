package com.naqqa.elasticsearch.index.mapper;

import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertNotNull;

public final class MiscFieldTypesTest {

    private static IndexableField find(List<IndexableField> fields, String name, IndexableField.Kind kind) {
        for (IndexableField f : fields) {
            if (f.name().equals(name) && f.kind() == kind) {
                return f;
            }
        }
        return null;
    }

    @Test
    public void unsignedLongAndScaledFloatAndHalfFloat() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "big", Map.of("type", "unsigned_long"),
            "price", Map.of("type", "scaled_float", "scaling_factor", 100),
            "small", Map.of("type", "half_float"))));
        ParsedDocument doc = ms.parse("1", null, Map.of("big", "18446744073709551615", "price", 19.99, "small", 1.5));
        assertNotNull(find(doc.rootFields(), "big", IndexableField.Kind.NUMERIC_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "price", IndexableField.Kind.NUMERIC_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "small", IndexableField.Kind.NUMERIC_DOC_VALUES));
    }

    @Test
    public void wildcardFlattenedJoinSearchAsYouType() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "path", Map.of("type", "wildcard"),
            "meta", Map.of("type", "flattened"),
            "rel", Map.of("type", "join"),
            "titleSayt", Map.of("type", "search_as_you_type"))));
        ParsedDocument doc = ms.parse("1", null, Map.of(
            "path", "/a/b/c",
            "meta", Map.of("x", "1", "y", Map.of("z", "2")),
            "rel", Map.of("name", "child", "parent", "p1"),
            "titleSayt", "hello there"));
        assertNotNull(find(doc.rootFields(), "path", IndexableField.Kind.INDEXED_TEXT));
        assertNotNull(find(doc.rootFields(), "meta", IndexableField.Kind.SORTED_SET_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "rel#child", IndexableField.Kind.INDEXED_TEXT));
        assertNotNull(find(doc.rootFields(), "titleSayt", IndexableField.Kind.INDEXED_TEXT));
    }

    @Test
    public void tokenCountAliasConstantKeywordVersion() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "body", Map.of("type", "text"),
            "bodyLength", Map.of("type", "token_count", "analyzer", "standard"),
            "tenant", Map.of("type", "constant_keyword", "value", "acme"),
            "ver", Map.of("type", "version"),
            "aliasField", Map.of("type", "alias", "path", "body"))));
        ParsedDocument doc = ms.parse("1", null, Map.of(
            "body", "one two three",
            "bodyLength", "one two three",
            "tenant", "acme",
            "ver", "1.2.3"));
        assertEquals(3L, find(doc.rootFields(), "bodyLength", IndexableField.Kind.NUMERIC_DOC_VALUES).numericValue());
        assertNotNull(find(doc.rootFields(), "tenant", IndexableField.Kind.SORTED_SET_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "ver", IndexableField.Kind.SORTED_SET_DOC_VALUES));
    }

    @Test
    public void histogramRankFeatureRankFeaturesSparseVectorPercolator() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "hist", Map.of("type", "histogram"),
            "popularity", Map.of("type", "rank_feature"),
            "features", Map.of("type", "rank_features"),
            "sparseVec", Map.of("type", "sparse_vector"),
            "query", Map.of("type", "percolator"))));
        ParsedDocument doc = ms.parse("1", null, Map.of(
            "hist", Map.of("values", List.of(1.0, 2.0), "counts", List.of(3, 4)),
            "popularity", 2.5,
            "features", Map.of("sports", 3.0, "news", 1.0),
            "sparseVec", Map.of("10", 0.5, "20", 0.8),
            "query", Map.of("match", Map.of("body", "foo"))));
        assertNotNull(find(doc.rootFields(), "hist", IndexableField.Kind.BINARY_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "popularity", IndexableField.Kind.NUMERIC_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "features", IndexableField.Kind.BINARY_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "sparseVec", IndexableField.Kind.BINARY_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "query", IndexableField.Kind.STORED));
    }

    @Test
    public void geoShapeAndShapeAndPoint() {
        MapperService ms = MapperServiceTestSupport.newMapperService();
        ms.putMapping(Map.of("properties", Map.of(
            "area", Map.of("type", "geo_shape"),
            "cartesianArea", Map.of("type", "shape"),
            "xy", Map.of("type", "point"))));
        ParsedDocument doc = ms.parse("1", null, Map.of(
            "area", "POINT (30 10)",
            "cartesianArea", "POINT (1 2)",
            "xy", Map.of("x", 1.0, "y", 2.0)));
        assertNotNull(find(doc.rootFields(), "area", IndexableField.Kind.BINARY_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "cartesianArea", IndexableField.Kind.BINARY_DOC_VALUES));
        assertNotNull(find(doc.rootFields(), "xy", IndexableField.Kind.NUMERIC_DOC_VALUES));
    }
}
