package com.naqqa.elasticsearch.node;

import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public class NodeSearchFeaturesTest {

    private static final String MAPPING = "{\"settings\":{\"number_of_shards\":2},\"mappings\":{\"properties\":{"
        + "\"title\":{\"type\":\"text\",\"term_vector\":\"with_positions_offsets\"},"
        + "\"body\":{\"type\":\"text\"},"
        + "\"summary\":{\"type\":\"text\",\"store\":true},"
        + "\"tag\":{\"type\":\"keyword\"},\"author\":{\"type\":\"keyword\"},"
        + "\"views\":{\"type\":\"integer\"},\"rating\":{\"type\":\"double\"},\"published\":{\"type\":\"date\"},"
        + "\"suggest\":{\"type\":\"completion\"},"
        + "\"vec\":{\"type\":\"dense_vector\",\"dims\":3,\"similarity\":\"l2_norm\"}}}}";

    private static final String[][] DOCS = {
        {"1", "quick brown fox", "the quick brown fox jumps over the lazy dog", "animals", "alice", "10", "4.5", "2024-01-01",
            "{\"input\":[\"Nirvana\",\"Nevermind\"],\"weight\":34}", "[1.0,0.0,0.0]"},
        {"2", "lazy dog sleeps", "a lazy dog sleeps all day long", "animals", "bob", "5", "3.0", "2024-02-01",
            "{\"input\":[\"Nickelback\"],\"weight\":10}", "[0.0,1.0,0.0]"},
        {"3", "brown bear", "the brown bear eats honey in the forest", "nature", "alice", "50", "4.9", "2024-03-01",
            "{\"input\":[\"Nine Inch Nails\"],\"weight\":20}", "[0.0,0.0,1.0]"},
        {"4", "quick recipes", "quick and easy recipes for the whole family", "food", "carol", "30", "2.5", "2024-04-01",
            null, "[0.9,0.1,0.0]"},
        {"5", "fox news", "the fox reporter covers the brown fox story", "news", "bob", "1", "1.0", "2024-05-01",
            null, "[0.5,0.5,0.0]"},
        {"6", "forest walks", "walking in the forest with a dog", "nature", "carol", "20", "3.9", "2024-06-01",
            null, "[0.0,0.2,0.8]"},
    };

    private static void ok(NodeTestSupport.Response r) {
        assertTrue(r.status() >= 200 && r.status() < 300, "status " + r.status() + ": " + r.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hitsSection(NodeTestSupport.Response r) {
        return (Map<String, Object>) r.json().get("hits");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hits(NodeTestSupport.Response r) {
        return (List<Map<String, Object>>) hitsSection(r).get("hits");
    }

    @SuppressWarnings("unchecked")
    private static long total(NodeTestSupport.Response r) {
        return ((Number) ((Map<String, Object>) hitsSection(r).get("total")).get("value")).longValue();
    }

    private static List<String> ids(NodeTestSupport.Response r) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> h : hits(r)) {
            out.add(String.valueOf(h.get("_id")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static void load(NodeTestSupport es, String index) throws Exception {
        ok(es.request("PUT", "/" + index, MAPPING));
        StringBuilder bulk = new StringBuilder();
        for (String[] d : DOCS) {
            bulk.append("{\"index\":{\"_index\":\"").append(index).append("\",\"_id\":\"").append(d[0]).append("\"}}\n");
            bulk.append("{\"title\":\"").append(d[1]).append("\",\"body\":\"").append(d[2]).append("\",\"summary\":\"summary of ")
                .append(d[1]).append("\",\"tag\":\"").append(d[3]).append("\",\"author\":\"").append(d[4]).append("\",\"views\":")
                .append(d[5]).append(",\"rating\":").append(d[6]).append(",\"published\":\"").append(d[7]).append("\"");
            if (d[8] != null) {
                bulk.append(",\"suggest\":").append(d[8]);
            }
            bulk.append(",\"vec\":").append(d[9]).append("}\n");
        }
        NodeTestSupport.Response r = es.request("POST", "/_bulk?refresh=true", bulk.toString());
        ok(r);
        assertEquals(Boolean.FALSE, r.json().get("errors"), r.body());
    }

    @Test
    public void testHighlightersOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("highlight")) {
            load(es, "articles");
            NodeTestSupport.Response unified = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"body\":\"honey\"}},\"highlight\":{\"fields\":{\"body\":{}}}}");
            ok(unified);
            assertEquals(List.of("3"), ids(unified), unified.body());
            String fragment = String.valueOf(list(map(hits(unified).get(0).get("highlight")).get("body")).get(0));
            assertTrue(fragment.contains("<em>honey</em>"), unified.body());

            NodeTestSupport.Response plain = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"body\":\"fox\"}},\"highlight\":{\"type\":\"plain\",\"pre_tags\":[\"<b>\"],"
                    + "\"post_tags\":[\"</b>\"],\"fields\":{\"body\":{\"number_of_fragments\":0}}}}");
            ok(plain);
            assertEquals(2L, total(plain), plain.body());
            for (Map<String, Object> hit : hits(plain)) {
                String hl = String.valueOf(list(map(hit.get("highlight")).get("body")).get(0));
                assertTrue(hl.contains("<b>fox</b>"), plain.body());
                assertFalse(hl.contains("<em>"), plain.body());
            }
            Map<String, Object> five = hits(plain).stream().filter(h -> "5".equals(h.get("_id"))).findFirst().orElseThrow();
            assertEquals("the <b>fox</b> reporter covers the brown <b>fox</b> story",
                list(map(five.get("highlight")).get("body")).get(0), plain.body());

            NodeTestSupport.Response fvh = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"title\":\"brown\"}},\"highlight\":{\"fields\":{\"title\":{\"type\":\"fvh\"}}}}");
            ok(fvh);
            assertEquals(2L, total(fvh), fvh.body());
            for (Map<String, Object> hit : hits(fvh)) {
                assertTrue(String.valueOf(map(hit.get("highlight")).get("title")).contains("<em>brown</em>"), fvh.body());
            }

            NodeTestSupport.Response fvhNoVectors = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"body\":\"brown\"}},\"highlight\":{\"fields\":{\"body\":{\"type\":\"fvh\"}}}}");
            assertEquals(400, fvhNoVectors.status(), fvhNoVectors.body());

            NodeTestSupport.Response wildcard = es.request("POST", "/articles/_search",
                "{\"query\":{\"multi_match\":{\"query\":\"forest\",\"fields\":[\"title\",\"body\"]}},\"highlight\":{\"fields\":{\"*\":{}},"
                    + "\"require_field_match\":false}}");
            ok(wildcard);
            Map<String, Object> six = hits(wildcard).stream().filter(h -> "6".equals(h.get("_id"))).findFirst().orElseThrow();
            Map<String, Object> hl = map(six.get("highlight"));
            assertTrue(String.valueOf(hl.get("title")).contains("<em>forest</em>"), wildcard.body());
            assertTrue(String.valueOf(hl.get("body")).contains("<em>forest</em>"), wildcard.body());
        }
    }

    @Test
    public void testSuggestersOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("suggest")) {
            load(es, "articles");
            NodeTestSupport.Response term = es.request("POST", "/articles/_search",
                "{\"size\":0,\"suggest\":{\"fix\":{\"text\":\"quikc\",\"term\":{\"field\":\"body\"}}}}");
            ok(term);
            List<Object> entries = list(map(term.json().get("suggest")).get("fix"));
            assertEquals(1, entries.size(), term.body());
            List<Object> options = list(map(entries.get(0)).get("options"));
            assertFalse(options.isEmpty(), term.body());
            assertEquals("quick", map(options.get(0)).get("text"), term.body());
            assertEquals(2L, ((Number) map(options.get(0)).get("freq")).longValue(), term.body());

            NodeTestSupport.Response phrase = es.request("POST", "/articles/_search",
                "{\"size\":0,\"suggest\":{\"did_you_mean\":{\"text\":\"lazzy dogg\",\"phrase\":{\"field\":\"body\","
                    + "\"highlight\":{\"pre_tag\":\"<em>\",\"post_tag\":\"</em>\"}}}}}");
            ok(phrase);
            List<Object> phraseOptions = list(map(list(map(phrase.json().get("suggest")).get("did_you_mean")).get(0)).get("options"));
            assertFalse(phraseOptions.isEmpty(), phrase.body());
            assertEquals("lazy dog", map(phraseOptions.get(0)).get("text"), phrase.body());
            assertEquals("<em>lazy</em> <em>dog</em>", map(phraseOptions.get(0)).get("highlighted"), phrase.body());

            NodeTestSupport.Response completion = es.request("POST", "/articles/_search",
                "{\"_source\":false,\"suggest\":{\"song\":{\"prefix\":\"ni\",\"completion\":{\"field\":\"suggest\",\"size\":3}}}}");
            ok(completion);
            List<Object> songOptions = list(map(list(map(completion.json().get("suggest")).get("song")).get(0)).get("options"));
            assertEquals(3, songOptions.size(), completion.body());
            assertEquals("Nirvana", map(songOptions.get(0)).get("text"), completion.body());
            assertEquals("1", map(songOptions.get(0)).get("_id"), completion.body());
            assertEquals("Nine Inch Nails", map(songOptions.get(1)).get("text"), completion.body());
            assertEquals("Nickelback", map(songOptions.get(2)).get("text"), completion.body());

            NodeTestSupport.Response fuzzy = es.request("POST", "/articles/_search",
                "{\"suggest\":{\"song\":{\"prefix\":\"nirv\",\"completion\":{\"field\":\"suggest\",\"fuzzy\":{\"fuzziness\":1}}}}}");
            ok(fuzzy);
            List<Object> fuzzyOptions = list(map(list(map(fuzzy.json().get("suggest")).get("song")).get(0)).get("options"));
            assertEquals("Nirvana", map(fuzzyOptions.get(0)).get("text"), fuzzy.body());
        }
    }

    private static List<Object> completionOptions(NodeTestSupport.Response r) {
        return list(map(list(map(r.json().get("suggest")).get("s")).get(0)).get("options"));
    }

    private static List<String> completionTexts(NodeTestSupport.Response r) {
        List<String> out = new ArrayList<>();
        for (Object o : completionOptions(r)) {
            out.add(String.valueOf(map(o).get("text")));
        }
        return out;
    }

    @Test
    public void testCompletionSuggestAcrossSegmentsDeletesAndMerge() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("completion-segments")) {
            String mapping = "{\"settings\":{\"number_of_shards\":1},\"mappings\":{\"properties\":{"
                + "\"suggest\":{\"type\":\"completion\"}}}}";
            ok(es.request("PUT", "/completions", mapping));

            ok(es.request("POST", "/_bulk?refresh=true",
                "{\"index\":{\"_index\":\"completions\",\"_id\":\"1\"}}\n"
                    + "{\"suggest\":{\"input\":[\"alpha one\"],\"weight\":10}}\n"
                    + "{\"index\":{\"_index\":\"completions\",\"_id\":\"2\"}}\n"
                    + "{\"suggest\":{\"input\":[\"alpha two\"],\"weight\":10}}\n"
                    + "{\"index\":{\"_index\":\"completions\",\"_id\":\"5\"}}\n"
                    + "{\"suggest\":{\"input\":[\"alpha five\"],\"weight\":100}}\n"));

            ok(es.request("POST", "/_bulk?refresh=true",
                "{\"index\":{\"_index\":\"completions\",\"_id\":\"3\"}}\n"
                    + "{\"suggest\":{\"input\":[\"alpha three\"],\"weight\":20}}\n"
                    + "{\"index\":{\"_index\":\"completions\",\"_id\":\"4\"}}\n"
                    + "{\"suggest\":{\"input\":[\"alpha one\"],\"weight\":15}}\n"));

            String prefixQuery = "{\"_source\":false,\"suggest\":{\"s\":{\"prefix\":\"alpha\","
                + "\"completion\":{\"field\":\"suggest\",\"size\":10}}}}";
            NodeTestSupport.Response beforeDelete = es.request("POST", "/completions/_search", prefixQuery);
            ok(beforeDelete);
            assertEquals(List.of("alpha five", "alpha three", "alpha one", "alpha one", "alpha two"),
                completionTexts(beforeDelete), beforeDelete.body());
            assertEquals("5", map(completionOptions(beforeDelete).get(0)).get("_id"), beforeDelete.body());
            assertEquals("3", map(completionOptions(beforeDelete).get(1)).get("_id"), beforeDelete.body());
            assertEquals("4", map(completionOptions(beforeDelete).get(2)).get("_id"), beforeDelete.body());
            assertEquals("1", map(completionOptions(beforeDelete).get(3)).get("_id"), beforeDelete.body());
            assertEquals("2", map(completionOptions(beforeDelete).get(4)).get("_id"), beforeDelete.body());

            String skipDupQuery = "{\"_source\":false,\"suggest\":{\"s\":{\"prefix\":\"alpha\","
                + "\"completion\":{\"field\":\"suggest\",\"size\":10,\"skip_duplicates\":true}}}}";
            NodeTestSupport.Response skipDup = es.request("POST", "/completions/_search", skipDupQuery);
            ok(skipDup);
            assertEquals(List.of("alpha five", "alpha three", "alpha one", "alpha two"),
                completionTexts(skipDup), skipDup.body());
            assertEquals("4", map(completionOptions(skipDup).get(2)).get("_id"), skipDup.body());

            String fuzzyQuery = "{\"_source\":false,\"suggest\":{\"s\":{\"prefix\":\"alppha\","
                + "\"completion\":{\"field\":\"suggest\",\"size\":10,\"fuzzy\":{\"fuzziness\":1}}}}}";
            NodeTestSupport.Response fuzzyBefore = es.request("POST", "/completions/_search", fuzzyQuery);
            ok(fuzzyBefore);
            assertEquals(List.of("alpha five", "alpha three", "alpha one", "alpha one", "alpha two"),
                completionTexts(fuzzyBefore), fuzzyBefore.body());

            ok(es.request("DELETE", "/completions/_doc/5", null));
            ok(es.request("POST", "/completions/_refresh", null));

            NodeTestSupport.Response afterDelete = es.request("POST", "/completions/_search", prefixQuery);
            ok(afterDelete);
            assertEquals(List.of("alpha three", "alpha one", "alpha one", "alpha two"),
                completionTexts(afterDelete), afterDelete.body());
            assertEquals("3", map(completionOptions(afterDelete).get(0)).get("_id"), afterDelete.body());
            assertEquals("4", map(completionOptions(afterDelete).get(1)).get("_id"), afterDelete.body());
            assertEquals("1", map(completionOptions(afterDelete).get(2)).get("_id"), afterDelete.body());
            assertEquals("2", map(completionOptions(afterDelete).get(3)).get("_id"), afterDelete.body());

            NodeTestSupport.Response fuzzyAfterDelete = es.request("POST", "/completions/_search", fuzzyQuery);
            ok(fuzzyAfterDelete);
            assertEquals(List.of("alpha three", "alpha one", "alpha one", "alpha two"),
                completionTexts(fuzzyAfterDelete), fuzzyAfterDelete.body());

            ok(es.request("POST", "/completions/_forcemerge?max_num_segments=1", null));

            NodeTestSupport.Response afterMerge = es.request("POST", "/completions/_search", prefixQuery);
            ok(afterMerge);
            assertEquals(completionTexts(afterDelete), completionTexts(afterMerge), afterMerge.body());
            assertEquals(List.of("3", "4", "1", "2"),
                List.of(String.valueOf(map(completionOptions(afterMerge).get(0)).get("_id")),
                    String.valueOf(map(completionOptions(afterMerge).get(1)).get("_id")),
                    String.valueOf(map(completionOptions(afterMerge).get(2)).get("_id")),
                    String.valueOf(map(completionOptions(afterMerge).get(3)).get("_id"))),
                afterMerge.body());

            NodeTestSupport.Response fuzzyAfterMerge = es.request("POST", "/completions/_search", fuzzyQuery);
            ok(fuzzyAfterMerge);
            assertEquals(completionTexts(fuzzyAfterDelete), completionTexts(fuzzyAfterMerge), fuzzyAfterMerge.body());
        }
    }

    @Test
    public void testCompletionSuggestScalesWithSegmentCountNotDocCount() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("completion-perf")) {
            String mapping = "{\"settings\":{\"number_of_shards\":1},\"mappings\":{\"properties\":{"
                + "\"suggest\":{\"type\":\"completion\"}}}}";
            ok(es.request("PUT", "/completions", mapping));

            int perBatch = 200;
            int batches = 10;
            for (int b = 0; b < batches; b++) {
                StringBuilder bulk = new StringBuilder();
                for (int i = 0; i < perBatch; i++) {
                    int id = b * perBatch + i;
                    bulk.append("{\"index\":{\"_index\":\"completions\",\"_id\":\"").append(id).append("\"}}\n");
                    bulk.append("{\"suggest\":{\"input\":[\"widget-").append(id)
                        .append("\"],\"weight\":").append(id).append("}}\n");
                }
                ok(es.request("POST", "/_bulk?refresh=true", bulk.toString()));
            }

            String query = "{\"_source\":false,\"suggest\":{\"s\":{\"prefix\":\"widget-1\","
                + "\"completion\":{\"field\":\"suggest\",\"size\":5}}}}";
            ok(es.request("POST", "/completions/_search", query));

            int iterations = 50;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                ok(es.request("POST", "/completions/_search", query));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            double avgMs = elapsedMs / (double) iterations;
            assertTrue(avgMs < 100.0, "average completion suggest latency too high once cached: " + avgMs + "ms");
        }
    }

    @Test
    public void testCollapseRescoreSearchAfterAndTotals() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("collapse")) {
            load(es, "articles");
            NodeTestSupport.Response collapse = es.request("POST", "/articles/_search",
                "{\"query\":{\"match_all\":{}},\"collapse\":{\"field\":\"author\",\"inner_hits\":{\"name\":\"top\",\"size\":2,"
                    + "\"sort\":[{\"views\":\"desc\"}]}},\"sort\":[{\"views\":\"desc\"}]}");
            ok(collapse);
            assertEquals(List.of("3", "4", "2"), ids(collapse), collapse.body());
            assertEquals(6L, total(collapse), collapse.body());
            Map<String, Object> alice = hits(collapse).get(0);
            assertEquals(List.of("alice"), map(alice.get("fields")).get("author"), collapse.body());
            Map<String, Object> inner = map(map(map(alice.get("inner_hits")).get("top")).get("hits"));
            assertEquals(2L, ((Number) map(inner.get("total")).get("value")).longValue(), collapse.body());
            List<Object> innerHits = list(inner.get("hits"));
            assertEquals("3", map(innerHits.get(0)).get("_id"), collapse.body());
            assertEquals("1", map(innerHits.get(1)).get("_id"), collapse.body());

            NodeTestSupport.Response plain = es.request("POST", "/articles/_search", "{\"query\":{\"match\":{\"body\":\"the\"}}}");
            ok(plain);
            assertFalse("3".equals(ids(plain).get(0)) && ids(plain).size() == 1, plain.body());
            NodeTestSupport.Response rescored = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"body\":\"the\"}},\"rescore\":{\"window_size\":10,\"query\":{\"rescore_query\":"
                    + "{\"match\":{\"body\":\"honey\"}},\"query_weight\":1.0,\"rescore_query_weight\":10.0}}}");
            ok(rescored);
            assertEquals("3", ids(rescored).get(0), rescored.body());
            assertEquals(total(plain), total(rescored), rescored.body());
            NodeTestSupport.Response rescoreWithSort = es.request("POST", "/articles/_search",
                "{\"sort\":[{\"views\":\"asc\"}],\"rescore\":{\"query\":{\"rescore_query\":{\"match_all\":{}}}}}");
            assertEquals(400, rescoreWithSort.status(), rescoreWithSort.body());

            NodeTestSupport.Response page1 = es.request("POST", "/articles/_search",
                "{\"size\":2,\"sort\":[{\"views\":\"asc\"}]}");
            ok(page1);
            assertEquals(List.of("5", "2"), ids(page1), page1.body());
            assertTrue(hits(page1).get(0).get("_score") == null, page1.body());
            List<Object> lastSort = list(hits(page1).get(1).get("sort"));
            assertEquals(5L, ((Number) lastSort.get(0)).longValue(), page1.body());
            NodeTestSupport.Response page2 = es.request("POST", "/articles/_search",
                "{\"size\":2,\"sort\":[{\"views\":\"asc\"}],\"search_after\":[" + lastSort.get(0) + "]}");
            ok(page2);
            assertEquals(List.of("1", "6"), ids(page2), page2.body());
            assertEquals(6L, total(page2), page2.body());

            NodeTestSupport.Response byKeyword = es.request("POST", "/articles/_search",
                "{\"sort\":[{\"author\":\"desc\"},{\"views\":\"asc\"}],\"size\":1}");
            ok(byKeyword);
            assertEquals("6", ids(byKeyword).get(0), byKeyword.body());
            assertEquals(List.of("carol", 20L), List.of(list(hits(byKeyword).get(0).get("sort")).get(0),
                ((Number) list(hits(byKeyword).get(0).get("sort")).get(1)).longValue()), byKeyword.body());

            NodeTestSupport.Response noTotal = es.request("POST", "/articles/_search", "{\"track_total_hits\":false}");
            ok(noTotal);
            assertTrue(hitsSection(noTotal).get("total") == null, noTotal.body());
            NodeTestSupport.Response capped = es.request("POST", "/articles/_search", "{\"track_total_hits\":2}");
            ok(capped);
            Map<String, Object> cappedTotal = map(hitsSection(capped).get("total"));
            assertEquals(2L, ((Number) cappedTotal.get("value")).longValue(), capped.body());
            assertEquals("gte", cappedTotal.get("relation"), capped.body());

            NodeTestSupport.Response fox = es.request("POST", "/articles/_search", "{\"query\":{\"match\":{\"body\":\"fox\"}}}");
            ok(fox);
            double max = ((Number) hitsSection(fox).get("max_score")).doubleValue();
            NodeTestSupport.Response minScore = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"body\":\"fox\"}},\"min_score\":" + (max - 1e-4) + "}");
            ok(minScore);
            assertTrue(total(minScore) >= 1 && total(minScore) < total(fox), minScore.body());

            NodeTestSupport.Response postFilter = es.request("POST", "/articles/_search",
                "{\"aggs\":{\"tags\":{\"terms\":{\"field\":\"tag\"}}},\"post_filter\":{\"term\":{\"tag\":\"nature\"}}}");
            ok(postFilter);
            assertEquals(2L, total(postFilter), postFilter.body());
            List<Object> buckets = list(map(map(postFilter.json().get("aggregations")).get("tags")).get("buckets"));
            assertEquals(4, buckets.size(), postFilter.body());

            NodeTestSupport.Response terminated = es.request("POST", "/articles/_search?terminate_after=1&timeout=10s", null);
            ok(terminated);
            assertEquals(Boolean.TRUE, terminated.json().get("terminated_early"), terminated.body());
            assertEquals(Boolean.FALSE, terminated.json().get("timed_out"), terminated.body());
            assertTrue(total(terminated) <= 2, terminated.body());
        }
    }

    @Test
    public void testFetchOptionsOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("fetch")) {
            load(es, "articles");
            NodeTestSupport.Response source = es.request("POST", "/articles/_search",
                "{\"query\":{\"ids\":{\"values\":[\"1\"]}},\"_source\":{\"includes\":[\"title\",\"views\",\"body\"],"
                    + "\"excludes\":[\"body\"]},\"version\":true,\"seq_no_primary_term\":true,"
                    + "\"docvalue_fields\":[\"published\",\"views\",\"tag\",{\"field\":\"published\",\"format\":\"epoch_millis\"}],"
                    + "\"script_fields\":{\"double_views\":{\"script\":{\"source\":\"doc['views'].value * 2\"}},"
                    + "\"with_param\":{\"script\":{\"source\":\"doc['rating'].value + params.bonus\",\"params\":{\"bonus\":1}}}}}");
            ok(source);
            Map<String, Object> hit = hits(source).get(0);
            assertEquals(Map.of("title", "quick brown fox", "views", 10L), normalize(map(hit.get("_source"))), source.body());
            assertEquals(1L, ((Number) hit.get("_version")).longValue(), source.body());
            assertTrue(hit.get("_seq_no") != null, source.body());
            assertEquals(1L, ((Number) hit.get("_primary_term")).longValue(), source.body());
            Map<String, Object> fields = map(hit.get("fields"));
            assertEquals("1704067200000", list(fields.get("published")).get(0), source.body());
            assertEquals(10L, ((Number) list(fields.get("views")).get(0)).longValue(), source.body());
            assertEquals(List.of("animals"), fields.get("tag"), source.body());
            assertEquals(20L, ((Number) list(fields.get("double_views")).get(0)).longValue(), source.body());
            assertEquals(5.5, ((Number) list(fields.get("with_param")).get(0)).doubleValue(), 1e-9);

            NodeTestSupport.Response isoDate = es.request("POST", "/articles/_search",
                "{\"query\":{\"ids\":{\"values\":[\"1\"]}},\"docvalue_fields\":[\"published\"]}");
            ok(isoDate);
            assertEquals("2024-01-01T00:00:00.000Z", list(map(hits(isoDate).get(0).get("fields")).get("published")).get(0), isoDate.body());

            NodeTestSupport.Response stored = es.request("POST", "/articles/_search?stored_fields=summary",
                "{\"query\":{\"ids\":{\"values\":[\"2\"]}}}");
            ok(stored);
            Map<String, Object> storedHit = hits(stored).get(0);
            assertTrue(storedHit.get("_source") == null, stored.body());
            assertEquals(List.of("summary of lazy dog sleeps"), map(storedHit.get("fields")).get("summary"), stored.body());

            NodeTestSupport.Response sourceParam = es.request("GET", "/articles/_search?q=_id:3&_source_includes=tag", null);
            ok(sourceParam);

            NodeTestSupport.Response explain = es.request("POST", "/articles/_search",
                "{\"explain\":true,\"query\":{\"match\":{\"body\":\"honey\"}}}");
            ok(explain);
            Map<String, Object> explained = hits(explain).get(0);
            assertTrue(explained.get("_shard") != null, explain.body());
            Map<String, Object> explanation = map(explained.get("_explanation"));
            assertTrue(((Number) explanation.get("value")).doubleValue() > 0, explain.body());

            NodeTestSupport.Response profile = es.request("POST", "/articles/_search",
                "{\"profile\":true,\"query\":{\"match\":{\"body\":\"fox\"}}}");
            ok(profile);
            List<Object> shards = list(map(profile.json().get("profile")).get("shards"));
            assertEquals(2, shards.size(), profile.body());
            Map<String, Object> search = map(list(map(shards.get(0)).get("searches")).get(0));
            assertFalse(list(search.get("query")).isEmpty(), profile.body());
            assertTrue(map(list(search.get("query")).get(0)).get("time_in_nanos") != null, profile.body());

            load(es, "articles2");
            NodeTestSupport.Response boosted = es.request("POST", "/articles,articles2/_search",
                "{\"query\":{\"match\":{\"body\":\"honey\"}},\"indices_boost\":[{\"articles2\":10}]}");
            ok(boosted);
            assertEquals(2L, total(boosted), boosted.body());
            assertEquals("articles2", hits(boosted).get(0).get("_index"), boosted.body());
            double s0 = ((Number) hits(boosted).get(0).get("_score")).doubleValue();
            double s1 = ((Number) hits(boosted).get(1).get("_score")).doubleValue();
            assertEquals(10.0, s0 / s1, 1e-3);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            out.put(e.getKey(), e.getValue() instanceof Number n && !(n instanceof Double) ? (Object) n.longValue() : e.getValue());
        }
        return out;
    }

    @Test
    public void testKnnAndRrfOverHttp() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("knn")) {
            load(es, "articles");
            NodeTestSupport.Response knn = es.request("POST", "/articles/_search",
                "{\"knn\":{\"field\":\"vec\",\"query_vector\":[1.0,0.0,0.0],\"k\":2,\"num_candidates\":10}}");
            ok(knn);
            assertEquals(List.of("1", "4"), ids(knn), knn.body());
            assertEquals(2L, total(knn), knn.body());

            NodeTestSupport.Response filtered = es.request("POST", "/articles/_search",
                "{\"knn\":{\"field\":\"vec\",\"query_vector\":[1.0,0.0,0.0],\"k\":1,\"num_candidates\":10,"
                    + "\"filter\":{\"term\":{\"tag\":\"nature\"}}}}");
            ok(filtered);
            assertEquals(List.of("6"), ids(filtered), filtered.body());

            NodeTestSupport.Response dsl = es.request("POST", "/articles/_search",
                "{\"query\":{\"knn\":{\"field\":\"vec\",\"query_vector\":[0.0,0.0,1.0],\"k\":1,\"num_candidates\":10}}}");
            ok(dsl);
            assertEquals("3", ids(dsl).get(0), dsl.body());

            NodeTestSupport.Response hybrid = es.request("POST", "/articles/_search",
                "{\"query\":{\"match\":{\"body\":\"honey\"}},\"knn\":{\"field\":\"vec\",\"query_vector\":[1.0,0.0,0.0],\"k\":1,"
                    + "\"num_candidates\":10}}");
            ok(hybrid);
            assertEquals(2L, total(hybrid), hybrid.body());
            assertTrue(ids(hybrid).containsAll(List.of("1", "3")), hybrid.body());

            NodeTestSupport.Response wrongField = es.request("POST", "/articles/_search",
                "{\"knn\":{\"field\":\"title\",\"query_vector\":[1.0,0.0,0.0],\"k\":1}}");
            assertEquals(400, wrongField.status(), wrongField.body());
            NodeTestSupport.Response wrongDims = es.request("POST", "/articles/_search",
                "{\"knn\":{\"field\":\"vec\",\"query_vector\":[1.0,0.0],\"k\":1}}");
            assertEquals(400, wrongDims.status(), wrongDims.body());

            NodeTestSupport.Response rrf = es.request("POST", "/articles/_search",
                "{\"retriever\":{\"rrf\":{\"retrievers\":[{\"standard\":{\"query\":{\"match\":{\"body\":\"fox\"}}}},"
                    + "{\"standard\":{\"query\":{\"term\":{\"tag\":\"nature\"}}}}],\"rank_window_size\":10,\"rank_constant\":60}}}");
            ok(rrf);
            assertEquals(4L, total(rrf), rrf.body());
            assertTrue(ids(rrf).containsAll(List.of("1", "3", "5", "6")), rrf.body());

            NodeTestSupport.Response hybridRrf = es.request("POST", "/articles/_search",
                "{\"size\":3,\"retriever\":{\"rrf\":{\"retrievers\":[{\"standard\":{\"query\":{\"match\":{\"body\":\"fox\"}}}},"
                    + "{\"knn\":{\"field\":\"vec\",\"query_vector\":[1.0,0.0,0.0],\"k\":2,\"num_candidates\":10}}]}}}");
            ok(hybridRrf);
            assertEquals("1", ids(hybridRrf).get(0), hybridRrf.body());
            assertEquals(3, ids(hybridRrf).size(), hybridRrf.body());
        }
    }

    @Test
    public void testAliasFiltersReindexRefreshStoredScriptsAndNested() throws Exception {
        try (NodeTestSupport es = new NodeTestSupport("aliases")) {
            load(es, "articles");
            load(es, "articles2");
            ok(es.request("POST", "/_aliases", "{\"actions\":["
                + "{\"add\":{\"index\":\"articles\",\"alias\":\"animal_docs\",\"filter\":{\"term\":{\"tag\":\"animals\"}}}},"
                + "{\"add\":{\"index\":\"articles2\",\"alias\":\"food_docs\",\"filter\":{\"term\":{\"tag\":\"food\"}}}}]}"));
            NodeTestSupport.Response twoAliases = es.request("POST", "/animal_docs,food_docs/_search", "{\"size\":20}");
            ok(twoAliases);
            assertEquals(3L, total(twoAliases), twoAliases.body());
            for (Map<String, Object> hit : hits(twoAliases)) {
                String expected = "articles".equals(hit.get("_index")) ? "animals" : "food";
                assertEquals(expected, map(hit.get("_source")).get("tag"), twoAliases.body());
            }
            NodeTestSupport.Response aliasPlusIndex = es.request("POST", "/animal_docs,articles2/_search", "{\"size\":0}");
            ok(aliasPlusIndex);
            assertEquals(8L, total(aliasPlusIndex), aliasPlusIndex.body());
            NodeTestSupport.Response aliasCount = es.request("POST", "/animal_docs,food_docs/_count", "{}");
            ok(aliasCount);
            assertEquals(3L, ((Number) aliasCount.json().get("count")).longValue(), aliasCount.body());
            NodeTestSupport.Response aliasQuery = es.request("POST", "/animal_docs,food_docs/_search",
                "{\"query\":{\"match\":{\"body\":\"dog\"}}}");
            ok(aliasQuery);
            assertEquals(List.of("1", "2"), ids(aliasQuery).stream().sorted().toList(), aliasQuery.body());
            NodeTestSupport.Response indexQuery = es.request("POST", "/articles,articles2/_search",
                "{\"size\":0,\"query\":{\"term\":{\"_index\":\"articles2\"}}}");
            ok(indexQuery);
            assertEquals(6L, total(indexQuery), indexQuery.body());

            ok(es.request("PUT", "/copy", "{\"mappings\":{\"properties\":{\"tag\":{\"type\":\"keyword\"}}}}"));
            NodeTestSupport.Response reindex = es.request("POST", "/_reindex?refresh=true",
                "{\"source\":{\"index\":\"articles\",\"query\":{\"term\":{\"tag\":\"nature\"}}},\"dest\":{\"index\":\"copy\"}}");
            ok(reindex);
            NodeTestSupport.Response copyCount = es.request("GET", "/copy/_count", null);
            assertEquals(2L, ((Number) copyCount.json().get("count")).longValue(), copyCount.body() + " / " + reindex.body());
            NodeTestSupport.Response reindexMax = es.request("POST", "/_reindex?refresh=true&max_docs=1",
                "{\"source\":{\"index\":\"articles2\"},\"dest\":{\"index\":\"copy2\"}}");
            ok(reindexMax);
            NodeTestSupport.Response copy2Count = es.request("GET", "/copy2/_count", null);
            assertEquals(1L, ((Number) copy2Count.json().get("count")).longValue(), copy2Count.body() + " / " + reindexMax.body());

            ok(es.request("PUT", "/_scripts/bump", "{\"script\":{\"lang\":\"painless\",\"source\":\"ctx._source.views += params.by\"}}"));
            NodeTestSupport.Response ubq = es.request("POST", "/articles/_update_by_query?refresh=true",
                "{\"query\":{\"term\":{\"author\":\"alice\"}},\"script\":{\"id\":\"bump\",\"params\":{\"by\":100}}}");
            ok(ubq);
            assertEquals(2L, ((Number) ubq.json().get("updated")).longValue(), ubq.body());
            NodeTestSupport.Response bumped = es.request("GET", "/articles/_doc/3", null);
            assertEquals(150L, ((Number) map(bumped.json().get("_source")).get("views")).longValue(), bumped.body());
            NodeTestSupport.Response badScript = es.request("PUT", "/_scripts/broken",
                "{\"script\":{\"lang\":\"painless\",\"source\":\"ctx._source.views += \"}}");
            assertEquals(400, badScript.status(), badScript.body());

            ok(es.request("PUT", "/blogs", "{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"},"
                + "\"comments\":{\"type\":\"nested\",\"properties\":{\"user\":{\"type\":\"keyword\"},\"text\":{\"type\":\"text\"}}}}}}"));
            ok(es.request("PUT", "/blogs/_doc/a", "{\"title\":\"first\",\"comments\":[{\"user\":\"ann\",\"text\":\"great post\"},"
                + "{\"user\":\"ben\",\"text\":\"boring\"}]}"));
            ok(es.request("PUT", "/blogs/_doc/b?refresh=true", "{\"title\":\"second\",\"comments\":[{\"user\":\"ann\",\"text\":\"boring\"},"
                + "{\"user\":\"ben\",\"text\":\"great stuff\"}]}"));
            NodeTestSupport.Response nested = es.request("POST", "/blogs/_search",
                "{\"query\":{\"nested\":{\"path\":\"comments\",\"query\":{\"bool\":{\"must\":[{\"term\":{\"comments.user\":\"ann\"}},"
                    + "{\"match\":{\"comments.text\":\"great\"}}]}}}}}");
            ok(nested);
            assertEquals(List.of("a"), ids(nested), nested.body());
            NodeTestSupport.Response all = es.request("GET", "/blogs/_search", null);
            ok(all);
            assertEquals(2L, total(all), all.body());
        }
    }
}
