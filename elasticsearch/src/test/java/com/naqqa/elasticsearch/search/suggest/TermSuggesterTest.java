package com.naqqa.elasticsearch.search.suggest;

import com.naqqa.elasticsearch.search.suggest.term.SuggestMode;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggestOptions;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggester;
import com.naqqa.elasticsearch.search.suggest.term.TermSuggestion;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;

public final class TermSuggesterTest {

    private List<String> corpus() {
        return List.of("hello", "hello", "hello", "help", "helm", "world", "word", "work", "cat", "cats", "car");
    }

    @Test
    public void returnsEditDistanceRankedCorrectionsForMisspelledWord() {
        TermSuggester suggester = new TermSuggester(corpus());
        TermSuggestOptions options = new TermSuggestOptions().suggestMode(SuggestMode.MISSING).maxEdits(2).prefixLength(0).minWordLength(1);
        List<TermSuggestion> results = suggester.suggest("helo", options);
        Assert.assertTrue(!results.isEmpty(), "expected suggestions for helo");
        Assert.assertEquals("hello", results.get(0).text());
        Assert.assertEquals(1, results.get(0).editDistance());
    }

    @Test
    public void popularModeOnlySuggestsMoreFrequentTerms() {
        TermSuggester suggester = new TermSuggester(corpus());
        TermSuggestOptions options = new TermSuggestOptions().suggestMode(SuggestMode.POPULAR).maxEdits(2).prefixLength(0).minWordLength(1);
        List<TermSuggestion> results = suggester.suggest("helm", options);
        for (TermSuggestion s : results) {
            Assert.assertTrue(s.frequency() > suggester.frequency("helm"), s.text());
        }
        Assert.assertTrue(results.stream().anyMatch(s -> s.text().equals("hello")));
    }

    @Test
    public void missingModeReturnsEmptyWhenWordIsKnown() {
        TermSuggester suggester = new TermSuggester(corpus());
        TermSuggestOptions options = new TermSuggestOptions().suggestMode(SuggestMode.MISSING).minWordLength(1);
        List<TermSuggestion> results = suggester.suggest("hello", options);
        Assert.assertTrue(results.isEmpty());
    }

    @Test
    public void prefixLengthRestrictsCandidates() {
        TermSuggester suggester = new TermSuggester(corpus());
        TermSuggestOptions options = new TermSuggestOptions().suggestMode(SuggestMode.ALWAYS).maxEdits(2).prefixLength(2).minWordLength(1);
        List<TermSuggestion> results = suggester.suggest("wo", options);
        for (TermSuggestion s : results) {
            Assert.assertTrue(s.text().startsWith("wo"), s.text());
        }
    }
}
