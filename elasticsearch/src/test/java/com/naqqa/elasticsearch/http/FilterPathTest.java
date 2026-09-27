package com.naqqa.elasticsearch.http;

import com.naqqa.elasticsearch.test.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FilterPathTest {

    @com.naqqa.elasticsearch.test.Test
    public void dottedIncludeKeepsAncestorsAndDropsOthers() {
        Map<String, Object> tree = sampleTree();

        Object filtered = FilterPath.apply(tree, List.of("took", "hits.hits._id"), List.of());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("took", 5);
        Map<String, Object> hits = new LinkedHashMap<>();
        hits.put("hits", List.of(Map.of("_id", "1"), Map.of("_id", "2")));
        expected.put("hits", hits);

        Assert.assertEquals(expected, filtered);
    }

    @com.naqqa.elasticsearch.test.Test
    public void doubleStarWithExclude() {
        Map<String, Object> tree = sampleTree();

        Object filtered = FilterPath.apply(tree, List.of("hits.hits.**"), List.of("hits.hits._source.b"));

        Map<String, Object> doc1 = new LinkedHashMap<>();
        doc1.put("_id", "1");
        doc1.put("_source", Map.of("a", 1));
        Map<String, Object> doc2 = new LinkedHashMap<>();
        doc2.put("_id", "2");
        doc2.put("_source", Map.of("a", 3));
        Map<String, Object> hits = new LinkedHashMap<>();
        hits.put("hits", List.of(doc1, doc2));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("hits", hits);

        Assert.assertEquals(expected, filtered);
    }

    @com.naqqa.elasticsearch.test.Test
    public void singleWildcardDropsNonMatchingSiblings() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("a", Map.of("x", 1));
        tree.put("b", Map.of("y", 2));

        Object filtered = FilterPath.apply(tree, List.of("*.x"), List.of());

        Assert.assertEquals(Map.of("a", Map.of("x", 1)), filtered);
    }

    @com.naqqa.elasticsearch.test.Test
    public void noPatternsReturnsSameTree() {
        Map<String, Object> tree = sampleTree();
        Object filtered = FilterPath.apply(tree, List.of(), List.of());
        Assert.assertSame(tree, filtered);
    }

    private static Map<String, Object> sampleTree() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("took", 5);
        Map<String, Object> hits = new LinkedHashMap<>();
        hits.put("total", 10);
        Map<String, Object> doc1 = new LinkedHashMap<>();
        doc1.put("_id", "1");
        Map<String, Object> source1 = new LinkedHashMap<>();
        source1.put("a", 1);
        source1.put("b", 2);
        doc1.put("_source", source1);
        Map<String, Object> doc2 = new LinkedHashMap<>();
        doc2.put("_id", "2");
        doc2.put("_source", Map.of("a", 3));
        hits.put("hits", List.of(doc1, doc2));
        tree.put("hits", hits);
        return tree;
    }
}
