package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class QueryParserRoundTripTest {

    private QueryBuilder roundTrip(Map<String, Object> json) {
        QueryBuilder parsed = QueryParser.parseQuery(json);
        QueryBuilder reparsed = QueryParser.parseQuery(parsed.toMap());
        Assert.assertEquals(parsed, reparsed);
        return parsed;
    }

    @Test
    public void matchQuery() {
        QueryBuilder q = roundTrip(Map.of("match", Map.of("message", "hello world")));
        Assert.assertTrue(q instanceof MatchQueryBuilder);
        MatchQueryBuilder m = (MatchQueryBuilder) q;
        Assert.assertEquals("message", m.fieldName());
        Assert.assertEquals("hello world", m.query());
    }

    @Test
    public void matchQueryWithParams() {
        QueryBuilder q = roundTrip(Map.of("match", Map.of("message",
            Map.of("query", "hello", "operator", "and", "minimum_should_match", "75%", "fuzziness", "AUTO"))));
        MatchQueryBuilder m = (MatchQueryBuilder) q;
        Assert.assertEquals(Operator.AND, m.operator());
        Assert.assertEquals("75%", m.minimumShouldMatch().asString());
        Assert.assertEquals("AUTO", m.fuzziness().asString());
    }

    @Test
    public void termQueryShorthandAndLong() {
        QueryBuilder q1 = roundTrip(Map.of("term", Map.of("status", "active")));
        Assert.assertTrue(q1 instanceof TermQueryBuilder);
        QueryBuilder q2 = roundTrip(Map.of("term", Map.of("status", Map.of("value", "active", "boost", 2.0))));
        TermQueryBuilder t2 = (TermQueryBuilder) q2;
        Assert.assertEquals(2.0f, t2.boost());
    }

    @Test
    public void rangeQuery() {
        QueryBuilder q = roundTrip(Map.of("range", Map.of("age", Map.of("gte", 10, "lte", 20))));
        RangeQueryBuilder r = (RangeQueryBuilder) q;
        Assert.assertEquals(10, r.from());
        Assert.assertEquals(20, r.to());
        Assert.assertTrue(r.includeLower());
        Assert.assertTrue(r.includeUpper());
    }

    @Test
    public void boolQuery() {
        QueryBuilder q = roundTrip(Map.of("bool", Map.of(
            "must", List.of(Map.of("term", Map.of("a", "1"))),
            "should", List.of(Map.of("term", Map.of("b", "2")), Map.of("term", Map.of("c", "3"))),
            "minimum_should_match", "1")));
        BoolQueryBuilder b = (BoolQueryBuilder) q;
        Assert.assertEquals(1, b.must().size());
        Assert.assertEquals(2, b.should().size());
        Assert.assertEquals("1", b.minimumShouldMatch().asString());
    }

    @Test
    public void nestedQuery() {
        QueryBuilder q = roundTrip(Map.of("nested", Map.of(
            "path", "comments",
            "query", Map.of("match", Map.of("comments.text", "great")),
            "score_mode", "max")));
        NestedQueryBuilder n = (NestedQueryBuilder) q;
        Assert.assertEquals("comments", n.path());
        Assert.assertEquals(NestedQueryBuilder.ScoreMode.MAX, n.scoreMode());
    }

    @Test
    public void functionScoreQuery() {
        QueryBuilder q = roundTrip(Map.of("function_score", Map.of(
            "query", Map.of("match_all", Map.of()),
            "functions", List.of(
                Map.of("filter", Map.of("term", Map.of("a", "b")), "weight", 2.0),
                Map.of("random_score", Map.of())),
            "score_mode", "sum",
            "boost_mode", "replace")));
        FunctionScoreQueryBuilder fs = (FunctionScoreQueryBuilder) q;
        Assert.assertEquals(2, fs.functions().size());
        Assert.assertEquals(FunctionScoreQueryBuilder.ScoreMode.SUM, fs.scoreMode());
        Assert.assertEquals(FunctionScoreQueryBuilder.BoostMode.REPLACE, fs.boostMode());
    }

    @Test
    public void geoDistanceQuery() {
        QueryBuilder q = roundTrip(Map.of("geo_distance", Map.of(
            "distance", "200km",
            "pin.location", Map.of("lat", 40.0, "lon", -70.0))));
        GeoDistanceQueryBuilder g = (GeoDistanceQueryBuilder) q;
        Assert.assertEquals("pin.location", g.fieldName());
        Assert.assertEquals("200km", g.distance());
    }

    @Test
    public void spanNearQuery() {
        QueryBuilder q = roundTrip(Map.of("span_near", Map.of(
            "clauses", List.of(
                Map.of("span_term", Map.of("field", "value1")),
                Map.of("span_term", Map.of("field", "value2"))),
            "slop", 2,
            "in_order", false)));
        Assert.assertEquals("span_near", q.getWriteableName());
    }

    @Test
    public void unknownQueryType() {
        try {
            QueryParser.parseQuery(Map.of("mach", Map.of("a", "b")));
            Assert.fail("expected ParsingException");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("match"), "expected suggestion in: " + e.getMessage());
        }
    }

    @Test
    public void unknownFieldInMatch() {
        try {
            QueryParser.parseQuery(Map.of("match", Map.of("f", Map.of("query", "x", "operater", "and"))));
            Assert.fail("expected ParsingException");
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("operator"), "expected suggestion in: " + e.getMessage());
        }
    }
}
