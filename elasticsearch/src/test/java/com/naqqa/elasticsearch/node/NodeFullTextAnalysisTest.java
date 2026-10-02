package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class NodeFullTextAnalysisTest {

    private static final String MAPPING = "{\"settings\":{\"number_of_shards\":1,\"analysis\":{"
        + "\"filter\":{\"edge\":{\"type\":\"edge_ngram\",\"min_gram\":1,\"max_gram\":20}},"
        + "\"normalizer\":{\"folded\":{\"type\":\"custom\",\"filter\":[\"lowercase\",\"asciifolding\"]}},"
        + "\"analyzer\":{\"folded_text\":{\"type\":\"custom\",\"tokenizer\":\"standard\",\"filter\":[\"lowercase\",\"asciifolding\"]},"
        + "\"folded_prefix\":{\"type\":\"custom\",\"tokenizer\":\"standard\",\"filter\":[\"lowercase\",\"asciifolding\",\"edge\"]}}}},"
        + "\"mappings\":{\"properties\":{"
        + "\"text\":{\"type\":\"text\",\"analyzer\":\"folded_text\",\"fields\":{"
        + "\"prefix\":{\"type\":\"text\",\"analyzer\":\"folded_prefix\",\"search_analyzer\":\"folded_text\"},"
        + "\"raw\":{\"type\":\"keyword\",\"normalizer\":\"folded\"}}},"
        + "\"tag\":{\"type\":\"keyword\"}}}}";

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hits(NodeTestSupport.Response r) {
        return (List<Map<String, Object>>) ((Map<String, Object>) r.json().get("hits")).get("hits");
    }

    @SuppressWarnings("unchecked")
    private static long total(NodeTestSupport.Response r) {
        Map<String, Object> section = (Map<String, Object>) r.json().get("hits");
        return ((Number) ((Map<String, Object>) section.get("total")).get("value")).longValue();
    }

    private static List<String> ids(NodeTestSupport.Response r) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> h : hits(r)) {
            out.add(String.valueOf(h.get("_id")));
        }
        return out;
    }

    private static void load(NodeTestSupport es) throws Exception {
        ok(es.request("PUT", "/products", MAPPING));
        ok(es.request("POST", "/products/_bulk?refresh=true",
            "{\"index\":{\"_id\":\"1\"}}\n{\"text\":[\"Lapte Ușor 2.5% Fermă\",\"Молоко лёгкое\"]}\n"
                + "{\"index\":{\"_id\":\"2\"}}\n{\"text\":[\"Pâine albă\",\"Хлеб белый\"]}\n"
                + "{\"index\":{\"_id\":\"3\"}}\n{\"text\":[\"Cafea Jacobs Monarch 250g\"]}\n"
                + "{\"index\":{\"_id\":\"4\"}}\n{\"text\":[\"endUser@gmail.com\",\"Chuck Norris\"]}\n"));
    }

    private static NodeTestSupport.Response search(NodeTestSupport es, String query) throws Exception {
        return es.request("POST", "/products/_search", "{\"size\":100,\"query\":" + query + "}");
    }

    @Test
    public void testMatchQueryTextIsAnalyzedWithFieldSearchAnalyzer() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("analyzed-match")) {
            load(es);
            NodeTestSupport.Response diacritics = search(es, "{\"match\":{\"text\":\"Ușor\"}}");
            ok(diacritics);
            assertEquals(List.of("1"), ids(diacritics), diacritics.body());

            NodeTestSupport.Response punctuation = search(es, "{\"match\":{\"text\":{\"query\":\"LAPTE, fermă!\",\"operator\":\"and\"}}}");
            ok(punctuation);
            assertEquals(List.of("1"), ids(punctuation), punctuation.body());

            NodeTestSupport.Response cyrillic = search(es, "{\"match\":{\"text\":\"ХЛЕБ\"}}");
            ok(cyrillic);
            assertEquals(List.of("2"), ids(cyrillic), cyrillic.body());

            NodeTestSupport.Response phrase = search(es, "{\"match_phrase\":{\"text\":\"Pâine Albă\"}}");
            ok(phrase);
            assertEquals(List.of("2"), ids(phrase), phrase.body());
        }
    }

    @Test
    public void testMultiFieldSubfieldUsesItsOwnSearchAnalyzer() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("analyzed-subfield")) {
            load(es);
            NodeTestSupport.Response prefix = search(es, "{\"match\":{\"text.prefix\":{\"query\":\"LAPTE, uș\",\"operator\":\"and\"}}}");
            ok(prefix);
            assertEquals(List.of("1"), ids(prefix), prefix.body());

            NodeTestSupport.Response cyrillicPrefix = search(es, "{\"match\":{\"text.prefix\":{\"query\":\"Мол\",\"operator\":\"and\"}}}");
            ok(cyrillicPrefix);
            assertEquals(List.of("1"), ids(cyrillicPrefix), cyrillicPrefix.body());

            NodeTestSupport.Response multi = search(es, "{\"multi_match\":{\"query\":\"Pâi\",\"fields\":[\"text\",\"text.prefix^2\"]}}");
            ok(multi);
            assertEquals(List.of("2"), ids(multi), multi.body());
        }
    }

    @Test
    public void testMatchAndMultiMatchHonourFuzziness() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("fuzzy-match")) {
            load(es);
            NodeTestSupport.Response withoutFuzziness = search(es, "{\"match\":{\"text\":\"jakobs\"}}");
            ok(withoutFuzziness);
            assertEquals(0L, total(withoutFuzziness), withoutFuzziness.body());

            NodeTestSupport.Response fuzzy = search(es, "{\"match\":{\"text\":{\"query\":\"cafe jakobs\",\"operator\":\"and\",\"fuzziness\":\"AUTO\",\"prefix_length\":1}}}");
            ok(fuzzy);
            assertEquals(List.of("3"), ids(fuzzy), fuzzy.body());

            NodeTestSupport.Response prefixGuard = search(es, "{\"match\":{\"text\":{\"query\":\"kacobs\",\"fuzziness\":1,\"prefix_length\":1}}}");
            ok(prefixGuard);
            assertEquals(0L, total(prefixGuard), prefixGuard.body());

            NodeTestSupport.Response multi = search(es, "{\"multi_match\":{\"query\":\"norri\",\"fields\":[\"text\"],\"fuzziness\":\"AUTO\"}}");
            ok(multi);
            assertEquals(List.of("4"), ids(multi), multi.body());

            NodeTestSupport.Response exactFirst = es.request("POST", "/products/_search",
                "{\"query\":{\"match\":{\"text\":{\"query\":\"alba\",\"fuzziness\":1}}}}");
            ok(exactFirst);
            assertEquals("2", ids(exactFirst).get(0), exactFirst.body());
        }
    }

    @Test
    public void testWildcardOverManyTermsMatchesEveryDocumentWithConstantScore() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("wildcard-many-terms")) {
            ok(es.request("PUT", "/codes", "{\"settings\":{\"number_of_shards\":1},\"mappings\":{\"properties\":{\"k\":{\"type\":\"keyword\"}}}}"));
            StringBuilder bulk = new StringBuilder();
            for (int i = 0; i < 300; i++) {
                bulk.append("{\"index\":{\"_id\":\"").append(i).append("\"}}\n");
                bulk.append("{\"k\":[\"xa").append(i).append("\",\"yb").append(i).append("\"]}\n");
                if (i == 149) {
                    ok(es.request("POST", "/codes/_bulk?refresh=true", bulk.toString()));
                    bulk.setLength(0);
                }
            }
            ok(es.request("POST", "/codes/_bulk?refresh=true", bulk.toString()));

            NodeTestSupport.Response all = es.request("POST", "/codes/_search",
                "{\"size\":1000,\"track_total_hits\":true,\"query\":{\"wildcard\":{\"k\":{\"value\":\"*a*\",\"boost\":2}}}}");
            ok(all);
            assertEquals(300L, total(all), all.body());
            Set<String> distinct = new HashSet<>(ids(all));
            assertEquals(300, distinct.size(), all.body());
            for (Map<String, Object> hit : hits(all)) {
                assertTrue(Math.abs(((Number) hit.get("_score")).doubleValue() - 2.0) < 1e-6, all.body());
            }

            NodeTestSupport.Response prefix = es.request("POST", "/codes/_search",
                "{\"size\":0,\"track_total_hits\":true,\"query\":{\"wildcard\":{\"k\":{\"value\":\"xa1*\"}}}}");
            ok(prefix);
            assertEquals(111L, total(prefix), prefix.body());

            NodeTestSupport.Response caseInsensitive = es.request("POST", "/codes/_search",
                "{\"size\":0,\"track_total_hits\":true,\"query\":{\"wildcard\":{\"k\":{\"value\":\"*B2*\",\"case_insensitive\":true}}}}");
            ok(caseInsensitive);
            assertEquals(111L, total(caseInsensitive), caseInsensitive.body());
        }
    }

    @Test
    public void testLargeDisjunctionCountsAndRanksCorrectly() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("large-disjunction")) {
            ok(es.request("PUT", "/words", "{\"settings\":{\"number_of_shards\":1},\"mappings\":{\"properties\":{\"body\":{\"type\":\"text\"}}}}"));
            StringBuilder bulk = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                bulk.append("{\"index\":{\"_id\":\"").append(i).append("\"}}\n");
                bulk.append("{\"body\":\"word").append(i).append(i % 10 == 0 ? " word" + (i + 1) + " word" + (i + 2) : "").append("\"}\n");
            }
            ok(es.request("POST", "/words/_bulk?refresh=true", bulk.toString()));
            StringBuilder query = new StringBuilder();
            for (int i = 0; i < 150; i++) {
                query.append("word").append(i).append(' ');
            }
            NodeTestSupport.Response r = es.request("POST", "/words/_search",
                "{\"size\":200,\"track_total_hits\":true,\"query\":{\"match\":{\"body\":\"" + query.toString().trim() + "\"}}}");
            ok(r);
            assertEquals(150L, total(r), r.body());
            assertTrue(ids(r).get(0).endsWith("0"), r.body());

            NodeTestSupport.Response msm = es.request("POST", "/words/_search",
                "{\"size\":200,\"track_total_hits\":true,\"query\":{\"match\":{\"body\":{\"query\":\"" + query.toString().trim()
                    + "\",\"minimum_should_match\":3}}}}");
            ok(msm);
            assertEquals(15L, total(msm), msm.body());
        }
    }
}
