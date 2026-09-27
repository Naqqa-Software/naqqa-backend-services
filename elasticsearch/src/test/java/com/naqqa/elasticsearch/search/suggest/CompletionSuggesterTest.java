package com.naqqa.elasticsearch.search.suggest;

import com.naqqa.elasticsearch.search.suggest.completion.CompletionEntry;
import com.naqqa.elasticsearch.search.suggest.completion.CompletionSuggester;
import com.naqqa.elasticsearch.search.suggest.completion.ContextQuery;
import com.naqqa.elasticsearch.search.suggest.completion.FuzzyOptions;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class CompletionSuggesterTest {

    private CompletionSuggester basic() {
        return new CompletionSuggester(List.of(
            new CompletionEntry.Input("java", 10),
            new CompletionEntry.Input("javascript", 30),
            new CompletionEntry.Input("java beans", 5),
            new CompletionEntry.Input("python", 20)
        ));
    }

    @Test
    public void prefixLookupOrdersByWeightDescending() {
        CompletionSuggester suggester = basic();
        List<CompletionEntry> results = suggester.suggest("java", 10, null);
        Assert.assertEquals(3, results.size());
        Assert.assertEquals("javascript", results.get(0).text());
        Assert.assertEquals("java", results.get(1).text());
        Assert.assertEquals("java beans", results.get(2).text());
    }

    @Test
    public void tiesAreBrokenByInsertionOrder() {
        CompletionSuggester suggester = new CompletionSuggester(List.of(
            new CompletionEntry.Input("alpha", 10),
            new CompletionEntry.Input("alphabet", 10),
            new CompletionEntry.Input("alphanumeric", 10)
        ));
        List<CompletionEntry> results = suggester.suggest("alpha", 10, null);
        Assert.assertEquals(3, results.size());
        Assert.assertEquals("alpha", results.get(0).text());
        Assert.assertEquals("alphabet", results.get(1).text());
        Assert.assertEquals("alphanumeric", results.get(2).text());
    }

    @Test
    public void sizeLimitsResults() {
        CompletionSuggester suggester = basic();
        List<CompletionEntry> results = suggester.suggest("java", 1, null);
        Assert.assertEquals(1, results.size());
        Assert.assertEquals("javascript", results.get(0).text());
    }

    @Test
    public void fuzzyCompletionMatchesWithinEditDistance() {
        CompletionSuggester suggester = basic();
        FuzzyOptions opts = new FuzzyOptions().maxEdits(1).fuzzyPrefixLength(1).fuzzyMinLength(3);
        List<CompletionEntry> results = suggester.suggestFuzzy("jaav", 10, opts, null);
        Assert.assertTrue(results.stream().anyMatch(e -> e.text().equals("java")), results.toString());
        Assert.assertTrue(results.stream().anyMatch(e -> e.text().equals("javascript")), results.toString());
    }

    @Test
    public void categoryContextFiltersResults() {
        CompletionSuggester suggester = new CompletionSuggester(List.of(
            new CompletionEntry.Input("pizza margherita", 10, Map.of("cuisine", List.of("italian"))),
            new CompletionEntry.Input("pizza teriyaki", 20, Map.of("cuisine", List.of("japanese"))),
            new CompletionEntry.Input("pizza calzone", 15, Map.of("cuisine", List.of("italian")))
        ));
        ContextQuery ctx = new ContextQuery().category("cuisine", "italian");
        List<CompletionEntry> results = suggester.suggest("pizza", 10, ctx);
        Assert.assertEquals(2, results.size());
        Assert.assertTrue(results.stream().allMatch(e -> e.contexts().get("cuisine").contains("italian")));
        Assert.assertEquals("pizza calzone", results.get(0).text());
    }

    @Test
    public void geoContextFiltersResultsByCellPrefix() {
        Map<String, List<String>> nearContext = Map.of("location", List.of(com.naqqa.elasticsearch.common.geo.Geohash.stringEncode(-0.1, 51.5, 6)));
        Map<String, List<String>> farContext = Map.of("location", List.of(com.naqqa.elasticsearch.common.geo.Geohash.stringEncode(139.7, 35.7, 6)));
        CompletionSuggester suggester = new CompletionSuggester(List.of(
            new CompletionEntry.Input("cafe london", 10, nearContext),
            new CompletionEntry.Input("cafe tokyo", 10, farContext)
        ));
        ContextQuery ctx = new ContextQuery().geo("location", -0.12, 51.5, 4);
        List<CompletionEntry> results = suggester.suggest("cafe", 10, ctx);
        Assert.assertEquals(1, results.size());
        Assert.assertEquals("cafe london", results.get(0).text());
    }
}
