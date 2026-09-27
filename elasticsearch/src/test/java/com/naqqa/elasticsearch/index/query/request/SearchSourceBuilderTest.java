package com.naqqa.elasticsearch.index.query.request;

import com.naqqa.elasticsearch.index.query.BoolQueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class SearchSourceBuilderTest {

    @Test
    public void fullRequestBody() {
        Map<String, Object> body = Map.ofEntries(
            Map.entry("query", Map.of("bool", Map.of("must", List.of(Map.of("match", Map.of("title", "elasticsearch")))))),
            Map.entry("from", 10),
            Map.entry("size", 20),
            Map.entry("sort", List.of(
                Map.of("price", "asc"),
                "_score",
                Map.of("_geo_distance", Map.of("pin.location", List.of(-70.0, 40.0), "order", "asc", "unit", "km")))),
            Map.entry("_source", Map.of("includes", List.of("title", "price"), "excludes", List.of("internal.*"))),
            Map.entry("post_filter", Map.of("term", Map.of("status", "published"))),
            Map.entry("min_score", 0.5),
            Map.entry("track_total_hits", true),
            Map.entry("collapse", Map.of("field", "user_id", "inner_hits", Map.of("name", "recent", "size", 3))),
            Map.entry("rescore", Map.of(
                "window_size", 50,
                "query", Map.of("rescore_query", Map.of("match_phrase", Map.of("title", Map.of("query", "elastic search", "slop", 2))),
                    "query_weight", 0.7, "rescore_query_weight", 1.2))),
            Map.entry("indices_boost", List.of(Map.of("index1", 1.4), Map.of("index2", 1.1))),
            Map.entry("aggs", Map.of("avg_price", Map.of("avg", Map.of("field", "price")))));

        SearchSourceBuilder builder = SearchSourceBuilder.fromMap(body);
        Assert.assertEquals(10, builder.from());
        Assert.assertEquals(20, builder.size());
        Assert.assertEquals(3, builder.sorts().size());
        Assert.assertTrue(builder.sorts().get(0) instanceof FieldSortBuilder);
        Assert.assertTrue(builder.sorts().get(1) instanceof ScoreSortBuilder);
        Assert.assertTrue(builder.sorts().get(2) instanceof GeoDistanceSortBuilder);
        Assert.assertEquals(List.of("title", "price"), builder.fetchSource().includes());
        Assert.assertEquals(List.of("internal.*"), builder.fetchSource().excludes());
        Assert.assertTrue(builder.query() instanceof BoolQueryBuilder);
        Assert.assertEquals("user_id", builder.collapse().field());
        Assert.assertEquals(1, builder.rescores().size());
        Assert.assertEquals(50, builder.rescores().get(0).windowSize().intValue());
        Assert.assertNotNull(builder.aggregations());

        Map<String, Object> roundTripped = builder.toMap();
        SearchSourceBuilder reparsed = SearchSourceBuilder.fromMap(roundTripped);
        Assert.assertEquals(builder, reparsed);
    }

    @Test
    public void sourceFilteringShorthands() {
        SearchSourceBuilder falseSrc = SearchSourceBuilder.fromMap(Map.of("_source", false));
        Assert.assertFalse(falseSrc.fetchSource().fetchSource());

        SearchSourceBuilder stringSrc = SearchSourceBuilder.fromMap(Map.of("_source", "obj.*"));
        Assert.assertEquals(List.of("obj.*"), stringSrc.fetchSource().includes());
    }

    @Test
    public void unknownTopLevelFieldThrows() {
        try {
            SearchSourceBuilder.fromMap(Map.of("bogus_field", "x"));
            Assert.fail("expected exception");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("bogus_field"));
        }
    }
}
