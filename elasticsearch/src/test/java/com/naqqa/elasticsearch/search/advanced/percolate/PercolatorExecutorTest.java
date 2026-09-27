package com.naqqa.elasticsearch.search.advanced.percolate;

import com.naqqa.elasticsearch.index.query.QueryBuilder;
import com.naqqa.elasticsearch.index.query.QueryParser;
import com.naqqa.elasticsearch.script.ScriptJson;
import com.naqqa.elasticsearch.test.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

public final class PercolatorExecutorTest {

    @Test
    public void identifiesMatchingAndNonMatchingStoredQueries() throws Exception {
        Map<String, QueryBuilder> storedQueries = new LinkedHashMap<>();
        storedQueries.put("q1", QueryParser.parseQuery(Map.of("term", Map.of("title", "elasticsearch"))));
        storedQueries.put("q2", QueryParser.parseQuery(Map.of("term", Map.of("title", "database"))));
        storedQueries.put("q3", QueryParser.parseQuery(Map.of("bool", Map.of(
            "must", List.of(
                Map.of("term", Map.of("title", "search")),
                Map.of("term", Map.of("title", "engine")))))));
        storedQueries.put("q4", QueryParser.parseQuery(Map.of("match_all", Map.of())));

        Map<String, String> document = Map.of("title", "elasticsearch is a search engine");

        List<PercolateMatch> matches = PercolatorExecutor.percolate(document, storedQueries);
        List<String> matchedIds = matches.stream().map(PercolateMatch::queryId).sorted().toList();
        assertEquals(List.of("q1", "q3", "q4"), matchedIds);
    }

    @Test
    public void multipleDocumentsAreMatchedIndependently() throws Exception {
        Map<String, QueryBuilder> storedQueries = Map.of(
            "onlyCats", QueryParser.parseQuery(Map.of("term", Map.of("text", "cat"))),
            "onlyDogs", QueryParser.parseQuery(Map.of("term", Map.of("text", "dog"))));
        List<Map<String, String>> docs = List.of(
            Map.of("text", "the cat sat"),
            Map.of("text", "the dog ran"));

        Map<Integer, List<PercolateMatch>> result = PercolatorExecutor.percolate(docs, storedQueries);
        assertEquals(1, result.get(0).size());
        assertEquals("onlyCats", result.get(0).get(0).queryId());
        assertEquals(1, result.get(1).size());
        assertEquals("onlyDogs", result.get(1).get(0).queryId());
    }

    @Test
    public void storedQueryDecodeRoundTripsThroughJson() {
        QueryBuilder builder = QueryParser.parseQuery(Map.of("match_all", Map.of()));
        String json = ScriptJson.toJson(builder.toMap());
        QueryBuilder decoded = StoredPercolatorQueries.decode(json);
        assertEquals(builder.toMap(), decoded.toMap());
    }
}
