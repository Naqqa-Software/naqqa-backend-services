package com.naqqa.elasticsearch.index.query;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class IntervalsQueryBuilderTest {

    @Test
    public void matchRule() {
        Map<String, Object> json = Map.of("intervals", Map.of("my_text", Map.of(
            "match", Map.of("query", "hello world", "max_gaps", 5, "ordered", true))));
        QueryBuilder q = QueryParser.parseQuery(json);
        Assert.assertTrue(q instanceof IntervalsQueryBuilder);
        IntervalsQueryBuilder iq = (IntervalsQueryBuilder) q;
        Assert.assertEquals("my_text", iq.fieldName());
        Assert.assertTrue(iq.rule() instanceof IntervalsQueryBuilder.Match);
        IntervalsQueryBuilder.Match m = (IntervalsQueryBuilder.Match) iq.rule();
        Assert.assertEquals("hello world", m.query());
        Assert.assertEquals(5, m.maxGaps().intValue());
        Assert.assertTrue(m.ordered());

        QueryBuilder reparsed = QueryParser.parseQuery(q.toMap());
        Assert.assertEquals(q, reparsed);
    }

    @Test
    public void allOfWithFilter() {
        Map<String, Object> json = Map.of("intervals", Map.of("my_text", Map.of(
            "all_of", Map.of(
                "intervals", List.of(
                    Map.of("match", Map.of("query", "the")),
                    Map.of("match", Map.of("query", "old"))),
                "max_gaps", 10,
                "filter", Map.of("not_containing", Map.of("match", Map.of("query", "bad")))))));
        QueryBuilder q = QueryParser.parseQuery(json);
        IntervalsQueryBuilder iq = (IntervalsQueryBuilder) q;
        Assert.assertTrue(iq.rule() instanceof IntervalsQueryBuilder.AllOf);
        IntervalsQueryBuilder.AllOf allOf = (IntervalsQueryBuilder.AllOf) iq.rule();
        Assert.assertEquals(2, allOf.intervals().size());
        Assert.assertEquals(10, allOf.maxGaps().intValue());
        Assert.assertEquals("not_containing", allOf.filter().type());

        QueryBuilder reparsed = QueryParser.parseQuery(q.toMap());
        Assert.assertEquals(q, reparsed);
    }

    @Test
    public void prefixAndWildcardAndFuzzy() {
        QueryBuilder q1 = QueryParser.parseQuery(Map.of("intervals", Map.of("f", Map.of("prefix", Map.of("prefix", "ab")))));
        Assert.assertTrue(((IntervalsQueryBuilder) q1).rule() instanceof IntervalsQueryBuilder.Prefix);

        QueryBuilder q2 = QueryParser.parseQuery(Map.of("intervals", Map.of("f", Map.of("wildcard", Map.of("pattern", "a*b")))));
        Assert.assertTrue(((IntervalsQueryBuilder) q2).rule() instanceof IntervalsQueryBuilder.Wildcard);

        QueryBuilder q3 = QueryParser.parseQuery(Map.of("intervals", Map.of("f", Map.of("fuzzy", Map.of("term", "abc")))));
        Assert.assertTrue(((IntervalsQueryBuilder) q3).rule() instanceof IntervalsQueryBuilder.Fuzzy);
    }
}
