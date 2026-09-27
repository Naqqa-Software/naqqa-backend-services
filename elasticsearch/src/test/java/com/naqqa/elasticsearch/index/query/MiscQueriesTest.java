package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.index.query.span.SpanNotQueryBuilder;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class MiscQueriesTest {

    private QueryBuilder roundTrip(Map<String, Object> json) {
        QueryBuilder parsed = QueryParser.parseQuery(json);
        QueryBuilder reparsed = QueryParser.parseQuery(parsed.toMap());
        Assert.assertEquals(parsed, reparsed);
        return parsed;
    }

    @Test
    public void queryStringViaRegistry() {
        QueryBuilder q = roundTrip(Map.of("query_string", Map.of("query", "title:elastic AND body:search", "default_operator", "and")));
        Assert.assertTrue(q instanceof QueryStringQueryBuilder);
        QueryStringQueryBuilder qs = (QueryStringQueryBuilder) q;
        QueryBuilder compiled = qs.toLuceneQuery();
        Assert.assertTrue(compiled instanceof BoolQueryBuilder);
        Assert.assertEquals(2, ((BoolQueryBuilder) compiled).must().size());
    }

    @Test
    public void simpleQueryStringTolerant() {
        QueryBuilder q = roundTrip(Map.of("simple_query_string", Map.of("query", "foo +bar -baz", "fields", List.of("title", "body"))));
        Assert.assertTrue(q instanceof SimpleQueryStringQueryBuilder);
        SimpleQueryStringQueryBuilder sq = (SimpleQueryStringQueryBuilder) q;
        QueryBuilder compiled = sq.toLuceneQuery();
        Assert.assertTrue(compiled instanceof BoolQueryBuilder);

        QueryBuilder tolerant = SimpleQueryStringParser.parse("(((unterminated", List.of("f"), Operator.OR);
        Assert.assertNotNull(tolerant);
    }

    @Test
    public void wrapperQueryDecodesBase64() {
        String raw = "{\"term\":{\"user\":\"kimchy\"}}";
        WrapperQueryBuilder w = WrapperQueryBuilder.fromSource(raw);
        Assert.assertEquals(raw, w.decodedSource());
        QueryBuilder reparsed = roundTrip(w.toMap());
        Assert.assertTrue(reparsed instanceof WrapperQueryBuilder);
    }

    @Test
    public void termsLookupShape() {
        QueryBuilder q = roundTrip(Map.of("terms", Map.of("user.id", Map.of("index", "users", "id", "2", "path", "followers"))));
        TermsQueryBuilder t = (TermsQueryBuilder) q;
        Assert.assertNotNull(t.lookup());
        Assert.assertEquals("users", t.lookup().index());
        Assert.assertEquals("followers", t.lookup().path());
    }

    @Test
    public void spanNotQuery() {
        QueryBuilder q = roundTrip(Map.of("span_not", Map.of(
            "include", Map.of("span_term", Map.of("field1", "hoya")),
            "exclude", Map.of("span_near", Map.of(
                "clauses", List.of(Map.of("span_term", Map.of("field1", "la")), Map.of("span_term", Map.of("field1", "hoya"))),
                "slop", 0, "in_order", true)))));
        Assert.assertTrue(q instanceof SpanNotQueryBuilder);
    }

    @Test
    public void geoShapeQuery() {
        QueryBuilder q = roundTrip(Map.of("geo_shape", Map.of("location", Map.of(
            "shape", Map.of("type", "envelope", "coordinates", List.of(List.of(13.0, 53.0), List.of(14.0, 52.0))),
            "relation", "within"))));
        Assert.assertTrue(q instanceof GeoShapeQueryBuilder);
        GeoShapeQueryBuilder gs = (GeoShapeQueryBuilder) q;
        Assert.assertEquals("location", gs.fieldName());
        Assert.assertNotNull(gs.shape());
    }

    @Test
    public void knnQueryShape() {
        QueryBuilder q = roundTrip(Map.of("knn", Map.of(
            "field", "image_vector",
            "query_vector", List.of(0.1, 0.2, 0.3),
            "k", 10,
            "num_candidates", 50)));
        Assert.assertTrue(q instanceof KnnQueryBuilder);
        KnnQueryBuilder knn = (KnnQueryBuilder) q;
        Assert.assertEquals(10, knn.k());
        Assert.assertEquals(3, knn.queryVector().size());
    }
}
